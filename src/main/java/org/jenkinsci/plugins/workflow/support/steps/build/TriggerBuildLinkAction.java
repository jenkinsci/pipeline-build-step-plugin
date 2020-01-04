package org.jenkinsci.plugins.workflow.support.steps.build;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;

import hudson.AbortException;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Describable;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.queue.QueueTaskFuture;
import hudson.model.queue.ScheduleResult;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

/**
 * Action that shows an button on the build page that allows the user to start
 * an downstream build whith predefined parameter values.
 */
public final class TriggerBuildLinkAction implements Action {

    private static final Logger LOGGER = Logger.getLogger(BuildTriggerStepExecution.class.getName());
    
    private transient Run<?, ?> run;
    private final String runId;
    private transient FlowNode node;
    private final String nodeId;
    
    private final String job;
    private final String linkLabel;
    private final String linkTooltip;
    private final List<ParameterValue> parameters;


    /**
     * Creates an new BuildTriggerButtonBuildAction.
     */
    public TriggerBuildLinkAction(
            Run<?, ?> run,
            String job,
            String linkLabel,
            String linkTooltip,
            List<ParameterValue> parameters,
            FlowNode node) {
        this.run = run;
        this.runId = run.getExternalizableId();
        this.node = node;
        this.nodeId = node.getId();

        this.job = job;
        this.linkLabel = linkLabel;
        this.linkTooltip = linkTooltip;
        this.parameters = parameters;
    }
    
    /**
     * Called when user pushes button and starts the downstream build.
     */
    public HttpResponse doStartBuild() {
        try {
            Run<?, ?> invokingRun = getRun();
            Item item = Jenkins.get().getItem(job, invokingRun.getParent(), Item.class);
            if (item == null) {
                throw new AbortException("No item named " + job + " found");
            }
            
            final List<Action> actions = new ArrayList<>();
            actions.add(
                new CauseAction(
                    new Cause.UpstreamCause(invokingRun), 
                    new Cause.UserIdCause()
                )
            );
            actions.add(new BuildUpstreamNodeAction(getNode(), invokingRun));
    
            if (item instanceof ParameterizedJobMixIn.ParameterizedJob) {
                final ParameterizedJobMixIn.ParameterizedJob project = (ParameterizedJobMixIn.ParameterizedJob) item;
    //            listener.getLogger().println("Scheduling project: " + ModelHyperlinkNote.encodeTo(project));
    
                getNode().addAction(new LabelAction(Messages.BuildTriggerStepExecution_building_(project.getFullDisplayName())));
    
                if (parameters != null) {
                    actions.add(new ParametersAction(parameters));
                }
                
                Queue.Item queueItem =
                    ParameterizedJobMixIn.scheduleBuild2(
                        (Job<?, ?>) project, -1, actions.toArray(new Action[actions.size()]));
                if (queueItem == null || queueItem.getFuture() == null) {
                    throw new AbortException("Failed to trigger build of " + project.getFullName());
                }
                
            } else if (item instanceof Queue.Task){
                if (parameters != null && !parameters.isEmpty()) {
                    throw new AbortException("Item type does not support parameters");
                }
                
                Queue.Task task = (Queue.Task) item;
                getNode().addAction(new LabelAction(Messages.BuildTriggerStepExecution_building_(task.getFullDisplayName())));
                
                Integer quietPeriod = null;
                try {
                    Method getQuietPeriod = task.getClass().getMethod("getQuietPeriod");
                    if (getQuietPeriod.getReturnType().equals(int.class)) {
                        quietPeriod = (Integer) getQuietPeriod.invoke(task);
                    }
                } catch (NoSuchMethodException e) {
                    // ignore, best effort only
                } catch (IllegalAccessError | IllegalArgumentException | InvocationTargetException e) {
                    LOGGER.log(Level.WARNING, "Could not determine quiet period of " + item.getFullName(), e);
                }
                if (quietPeriod == null) {
                    quietPeriod = Jenkins.get().getQuietPeriod();
                }
                ScheduleResult scheduleResult = Jenkins.get().getQueue().schedule2(task, quietPeriod, actions);
                if (scheduleResult.isRefused()) {
                    throw new AbortException("Failed to trigger build of " + item.getFullName());
                }
            } else {
                throw new AbortException("The item named " + item.getName() + " is a "
                    + (item instanceof Describable
                    ? ((Describable) item).getDescriptor().getDisplayName()
                    : item.getClass().getName())
                    + " which is not something that can be built");
            }
            return HttpResponses.forwardToPreviousPage();
        } catch (IOException |IllegalAccessException  e) {
            return HttpResponses.error(e);
        }
    }

    /**
     * Returns the {@link Run} which contains this action and is the upstream-build.
     * Performs lazy loading, if run is not been loaded yet.
     */
    private Run<?, ?> getRun() {
        if (run == null) {
            run = Run.fromExternalizableId(runId);
        }
        return run;
    }

    /**
     * Returns the {@link FlowNode} that has created the build link.
     * Performs lazy loading, if node has not been loaded yet.
     */
    private FlowNode getNode() throws IOException {
        if (node == null) {
            Run<?, ?> run = getRun();
            assert run instanceof WorkflowRun;
            WorkflowRun workflow = (WorkflowRun) run;
            FlowExecution execution = workflow.getExecution();
            if (execution != null) {
                node = execution.getNode(nodeId);
            } else {
                throw new IOException("Node could not be loaded: " + nodeId);
            }
        }
        return node;
    }

    /**
     * Return the label for the link.
     */
    public String getLinkLabel() {
        return linkLabel;
    }

    /**
     * Returns the tooltip for the link.
     */
    public String getLinkTooltip() {
        return linkTooltip;
    }

    /**
     * Should not appear in menu, so returns <code>null</code>.
     */
    @Override
    public String getIconFileName() {
        return null;
    }

    /**
     * Should not appear in menu, so returns <code>null</code>.
     */
    @Override
    public String getDisplayName() {
        return null;
    }
    
    @Override
    public String getUrlName() {
        return "triggerDownstreamBuild";
    }
    
    /**
     * Returns the jenkins-relative URL for starting the downstream build.
     */
    public String getTriggerUrl() {
        return getUrlName() + "/startBuild";
    }
}
