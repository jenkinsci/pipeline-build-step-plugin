package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.ProxyException;
import jenkins.model.CauseOfInterruption;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;

/** Copies used while serializing; running CPS callbacks keep their original exceptions. */
final class InterruptionSnapshot {
    private InterruptionSnapshot() {}

    static @NonNull Throwable copy(@NonNull Throwable original) {
        if (original instanceof FlowInterruptedException interruption) {
            // Keep the result and cancellation semantics when a waiting build resumes after restart.
            FlowInterruptedException snapshot = new FlowInterruptedException(
                    interruption.getResult(), interruption.isActualInterruption(),
                    interruption.getCauses().toArray(new CauseOfInterruption[0]));
            snapshot.setStackTrace(interruption.getStackTrace());
            Throwable cause = interruption.getCause();
            if (cause != null) {
                snapshot.initCause(new ProxyException(cause));
            }
            for (Throwable suppressed : interruption.getSuppressed()) {
                snapshot.addSuppressed(new ProxyException(suppressed));
            }
            return snapshot;
        }
        // ProxyException recursively copies causes and suppressed exceptions via synchronized
        // Throwable accessors, rather than iterating the live suppressedExceptions ArrayList.
        return new ProxyException(original);
    }
}
