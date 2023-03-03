package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Action;
import hudson.model.Actionable;
import hudson.model.InvisibleAction;
import hudson.model.Queue;
import hudson.model.queue.FoldableAction;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.steps.StepContext;

@SuppressWarnings("SynchronizeOnNonFinalField")
class BuildTriggerAction extends InvisibleAction implements FoldableAction {

    private static final Logger LOGGER = Logger.getLogger(BuildTriggerAction.class.getName());

    @Deprecated
    private StepContext context;

    @Deprecated
    private Boolean propagate;

    /** Record of one upstream build step. */
    static class Trigger {

        final StepContext context;

        final boolean propagate;
        final boolean waitForStart;

        /** Record of cancellation cause passed to {@link BuildTriggerStepExecution#stop}, if any. */
        @CheckForNull
        Throwable interruption;

        Trigger(StepContext context, boolean propagate, boolean waitForStart) {
            this.context = context;
            this.propagate = propagate;
            this.waitForStart = waitForStart;
        }

    }

    private /* final */ List<Trigger> triggers;

    BuildTriggerAction(StepContext context, boolean propagate, boolean waitForStart) {
        triggers = new ArrayList<>();
        triggers.add(new Trigger(context, propagate, waitForStart));
    }

    private Object readResolve() {
        if (triggers == null) {
            triggers = new ArrayList<>();
            triggers.add(new Trigger(context, propagate != null ? propagate : /* old serialized record */ true));
            context = null;
            propagate = null;
        }
        return this;
    }

    static Iterable<Trigger> triggersFor(Actionable actionable) {
        List<Trigger> triggers = new ArrayList<>();
        for (BuildTriggerAction action : actionable.getActions(BuildTriggerAction.class)) {
            synchronized (action.triggers) {
                triggers.addAll(action.triggers);
            }
        }
        return triggers;
    }

    @Override public void foldIntoExisting(Queue.Item item, Queue.Task owner, List<Action> otherActions) {
        // there may be >1 upstream builds (or other unrelated causes) for a single downstream build
        BuildTriggerAction existing = item.getAction(BuildTriggerAction.class);
        if (existing == null) {
            item.addAction(this);
        } else {
            synchronized (existing.triggers) {
                existing.triggers.addAll(triggers);
            }
        }
        LOGGER.log(Level.FINE, "coalescing actions for {0}", item);
    }

}
