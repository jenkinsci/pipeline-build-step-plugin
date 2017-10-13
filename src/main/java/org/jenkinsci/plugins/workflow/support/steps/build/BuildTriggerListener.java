package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.AbortException;
import hudson.Extension;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.util.Timer;
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
                    DownstreamBuildWriter b = new DownstreamBuildWriter("test", run, taskListener);
                    b.start();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, null, e);
                }
            } else {
                LOGGER.log(Level.FINE, "{0} unavailable in {1}", new Object[] {stepContext, run});
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation") // TODO 2.30+ use removeAction
    public void onCompleted(Run<?,?> run, @Nonnull TaskListener listener) {
        for (BuildTriggerAction.Trigger trigger : BuildTriggerAction.triggersFor(run)) {
            LOGGER.log(Level.FINE, "completing {0} for {1}", new Object[] {run, trigger.context});
            if (!trigger.propagate || run.getResult() == Result.SUCCESS) {
                if (trigger.interruption == null) {
                    trigger.context.onSuccess(new RunWrapper(run, false));
                } else {
                    trigger.context.onFailure(trigger.interruption);
                }
            } else {
                trigger.context.onFailure(new AbortException(run.getFullDisplayName() + " completed with status " + run.getResult() + " (propagate: false to ignore)"));
            }
        }
        run.getActions().removeAll(run.getActions(BuildTriggerAction.class));
    }

    @Override
    public void onDeleted(final Run<?,?> run) {
        for (final BuildTriggerAction.Trigger trigger : BuildTriggerAction.triggersFor(run)) {
            Timer.get().submit(new Runnable() {
                @Override public void run() {
                    trigger.context.onFailure(new AbortException(run.getFullDisplayName() + " was deleted"));
                }
            });
        }
    }
}
