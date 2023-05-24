package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;

public class WaitForBuildStepExecution extends AbstractStepExecutionImpl {

    private static final long serialVersionUID = 1L;

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
            taskListener.getLogger().println(runHyperLink + " already completed: " + result.toString());

            StepContext context = getContext();
            if (!step.isPropagate() || result == Result.SUCCESS) {
                context.onSuccess(new RunWrapper(run, false));
            } else {
                context.onFailure(new FlowInterruptedException(result, false, new DownstreamFailureCause(run)));
            }
            return true;
        }
    }

}
