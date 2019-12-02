package org.jenkinsci.plugins.workflow.support.steps.build;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import hudson.AbortException;
import hudson.model.Describable;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

/**
 * Execution for the {@link AddTriggerBuildLinkStep}.
 */
public class AddTriggerBuildLinkStepExecution extends StepExecution {

    private static final long serialVersionUID = 1L;

    private transient Run<?,?> invokingRun;
    private transient FlowNode node;
    private transient PrintStream logger;

    private final AddTriggerBuildLinkStep step;


    public AddTriggerBuildLinkStepExecution(
            final StepContext context,
            final AddTriggerBuildLinkStep step) throws IOException, InterruptedException {
        super(context);
        this.invokingRun = context.get(Run.class);
        this.node = context.get(FlowNode.class);
        this.logger = context.get(TaskListener.class).getLogger();
        this.step = step;
    }

    @SuppressWarnings({"rawtypes"}) // cannot get from ParameterizedJob back to ParameterizedJobMixIn trivially
    @Override
    public boolean start() throws Exception {
        final String job = this.step.getJob();
        final Item item = Jenkins.get().getItem(job, this.invokingRun.getParent(), Item.class);
        if (item == null) {
            throw new AbortException("No item named " + job + " found");
        }
        item.checkPermission(Item.BUILD);

        List<ParameterValue> parameters = this.step.getParameters();
        if (item instanceof ParameterizedJobMixIn.ParameterizedJob) {
            final ParameterizedJobMixIn.ParameterizedJob project = (ParameterizedJobMixIn.ParameterizedJob) item;

            if (parameters != null) {
                parameters = BuildTriggerStepExecution.completeDefaultParameters(parameters, (Job) project, invokingRun, logger);
            }

        } else if (item instanceof Queue.Task){
            if (this.step.getParameters() != null && !this.step.getParameters().isEmpty()) {
                throw new AbortException("Item type does not support parameters");
            }

        } else {
            throw new AbortException("The item named " + job + " is a "
                    + (item instanceof Describable
                    ? ((Describable) item).getDescriptor().getDisplayName()
                    : item.getClass().getName())
                    + " which is not something that can be built");
        }

        final TriggerBuildLinkAction buttonAction = new TriggerBuildLinkAction(
            this.invokingRun,
            this.step.getJob(),
            this.step.getLinkLabel(),
            this.step.getLinkTooltip(),
            parameters,
            this.node
        );

        this.invokingRun.addAction(buttonAction);
        this.getContext().onSuccess(null);
        return true;
    }
}
