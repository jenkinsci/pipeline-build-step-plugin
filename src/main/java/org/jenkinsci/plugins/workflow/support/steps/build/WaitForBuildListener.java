package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;

@Extension
public class WaitForBuildListener extends RunListener<Run<?,?>> {

    private static final Logger LOGGER = Logger.getLogger(WaitForBuildListener.class.getName());

    @Override
    public void onCompleted(Run<?,?> run, @NonNull TaskListener listener) {
        for (WaitForBuildAction action : run.getActions(WaitForBuildAction.class)) {
            StepContext context = action.context;
            LOGGER.log(Level.FINE, "completing {0} for {1}", new Object[] {run, context});
            if (!action.propagate || run.getResult() == Result.SUCCESS) {
                context.onSuccess(new RunWrapper(run, false));
            } else {
                Result result = run.getResult();
                context.onFailure(new FlowInterruptedException(result != null ? result : /* probably impossible */ Result.FAILURE, false, new DownstreamFailureCause(run)));
            }
        }
        run.removeActions(WaitForBuildAction.class);
    }
}
