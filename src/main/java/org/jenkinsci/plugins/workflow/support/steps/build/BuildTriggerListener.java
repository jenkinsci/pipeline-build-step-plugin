package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;

@Extension
public class BuildTriggerListener extends RunListener<Run<?,?>>{

    private static final Logger LOGGER = Logger.getLogger(BuildTriggerListener.class.getName());

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        for (BuildTriggerAction.Trigger trigger : BuildTriggerAction.triggersFor(run)) {
            StepContext stepContext = trigger.context;
            if (stepContext != null && stepContext.isReady()) {
                LOGGER.log(Level.FINE, "started building {0} from #{1} in {2}", new Object[] {run, run.getQueueId(), stepContext});
                try {
                    TaskListener taskListener = stepContext.get(TaskListener.class);
                    // encodeTo(Run) calls getDisplayName, which does not include the project name.
                    taskListener.getLogger().println("Starting building: " + ModelHyperlinkNote.encodeTo("/" + run.getUrl(), run.getFullDisplayName()));
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, null, e);
                }
            } else {
                LOGGER.log(Level.FINE, "{0} unavailable in {1}", new Object[] {stepContext, run});
            }
        }
    }

    @Override
    public void onCompleted(Run<?,?> run, @NonNull TaskListener listener) {
        for (BuildTriggerAction.Trigger trigger : BuildTriggerAction.triggersFor(run)) {
            StepContext stepContext = trigger.context;
            LOGGER.log(Level.FINE, "completing {0} for {1}", new Object[] {run, stepContext});
            Result result = run.getResult();
            if (result == null) { /* probably impossible */
                result = Result.FAILURE;
            }

            try {
                stepContext.get(TaskListener.class).getLogger().println("Build " + ModelHyperlinkNote.encodeTo("/" + run.getUrl(), run.getFullDisplayName()) + " completed: " + result.toString());
                if (result != Result.SUCCESS) {
                    stepContext.get(FlowNode.class).addOrReplaceAction(new WarningAction(result));
                }
            }  catch (Exception e) {
                LOGGER.log(Level.WARNING, null, e);
            }

            if (!trigger.propagate || result == Result.SUCCESS) {
                if (trigger.interruption == null) {
                    stepContext.onSuccess(new RunWrapper(run, false));
                } else {
                    stepContext.onFailure(trigger.interruption);
                }
            } else {
                stepContext.onFailure(new FlowInterruptedException(result, false, new DownstreamFailureCause(run)));
            }
        }
        run.removeActions(BuildTriggerAction.class);
    }

    @Override
    public void onDeleted(final Run<?,?> run) {
        for (final BuildTriggerAction.Trigger trigger : BuildTriggerAction.triggersFor(run)) {
            Timer.get().submit(() -> trigger.context.onFailure(new AbortException(run.getFullDisplayName() + " was deleted")));
        }
    }
}
