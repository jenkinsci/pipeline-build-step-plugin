package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
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

            Result result = run.getResult();
            try {
                context.get(TaskListener.class).getLogger().println("Build " + ModelHyperlinkNote.encodeTo("/" + run.getUrl(), run.getFullDisplayName()) + " completed: " + result.toString());
                if (result.isWorseThan(Result.SUCCESS)) {
                    context.get(FlowNode.class).addOrReplaceAction(new WarningAction(result));
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, null, e);
            }

            if (!action.propagate || result == Result.SUCCESS) {
                context.onSuccess(new RunWrapper(run, false));
            } else {
                context.onFailure(new FlowInterruptedException(result != null ? result : /* probably impossible */ Result.FAILURE, false, new DownstreamFailureCause(run)));
            }
        }
        run.removeActions(WaitForBuildAction.class);
    }
}
