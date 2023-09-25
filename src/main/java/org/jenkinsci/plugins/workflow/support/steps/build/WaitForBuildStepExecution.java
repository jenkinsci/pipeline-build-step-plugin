package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WaitForBuildStepExecution extends AbstractStepExecutionImpl {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(WaitForBuildStepExecution.class.getName());

    private final transient WaitForBuildStep step;

    public WaitForBuildStepExecution(WaitForBuildStep step, @NonNull StepContext context) {
        super(context);
        this.step = step;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean start() throws Exception {
        Run run = Run.fromExternalizableId(step.getRunId());
        if (run == null) {
            throw new AbortException("No build exists with runId " + step.getRunId());
        }

        String runHyperLink = ModelHyperlinkNote.encodeTo("/" + run.getUrl(), run.getFullDisplayName());
        TaskListener taskListener = getContext().get(TaskListener.class);
        if (run.isBuilding()) {
            run.addAction(new WaitForBuildAction(getContext(), step.isPropagate()));
            taskListener.getLogger().println("Waiting for " + runHyperLink + " to complete");
            return false;
        } else {
            Result result = run.getResult();
            if (result == null) {
                taskListener.getLogger().println("Warning: " + runHyperLink + " already completed but getResult() returned null. Treating the result of this build as a failure");
                result = Result.FAILURE;
            }
            else  {
                taskListener.getLogger().println(runHyperLink + " already completed: " + result.toString());
            }

            StepContext context = getContext();
            if (!step.isPropagate() || result == Result.SUCCESS) {
                context.onSuccess(new RunWrapper(run, false));
            } else {
                context.onFailure(new FlowInterruptedException(result, false, new DownstreamFailureCause(run)));
            }
            return true;
        }
    }

    @Override
    public void stop(@NonNull Throwable cause) throws Exception {
        StepContext context = getContext();
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            context.onFailure(cause);
            return;
        }

        boolean interrupted = false;

        // if there's any in-progress build already, abort that.
        // when the build is actually aborted, WaitForBuildListener will take notice and report the failure,
        // so this method shouldn't call getContext().onFailure()
        for (Computer c : jenkins.getComputers()) {
            for (Executor e : c.getAllExecutors()) {
                interrupted |= maybeInterrupt(e, cause, context);
            }
        }

        if (!interrupted) {
            super.stop(cause);
        }
    }

    private static boolean maybeInterrupt(Executor e, Throwable cause, StepContext context) {
        boolean interrupted = false;
        Queue.Executable exec = e.getCurrentExecutable();
        if (exec instanceof Run) {
            for(WaitForBuildAction waitForBuildAction : ((Run<?, ?>) exec).getActions(WaitForBuildAction.class)) {
                if (waitForBuildAction.context.equals(context)) {
                    e.interrupt(Result.ABORTED, new BuildTriggerCancelledCause(cause));
                    try {
                        ((Run<?, ?>) exec).save();
                    } catch (IOException x) {
                        LOGGER.log(Level.WARNING, "failed to save interrupt cause on " + exec, x);
                    }
                    interrupted = true;
                }
            }
        }
        return interrupted;
    }

}
