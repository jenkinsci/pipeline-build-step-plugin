package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.Result;
import hudson.remoting.ProxyException;
import java.io.IOException;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterruptionSnapshotTest {

    @Test
    void preservesInterruptionSemanticsWithoutSharingExceptionGraph() throws Exception {
        FlowInterruptedException original = new FlowInterruptedException(Result.ABORTED, false);
        IOException cause = new IOException("cause");
        IOException suppressed = new IOException("callback");
        cause.addSuppressed(suppressed);
        original.initCause(cause);
        original.addSuppressed(suppressed);

        FlowInterruptedException snapshot = assertInstanceOf(
                FlowInterruptedException.class, InterruptionSnapshot.copy(original));
        assertEquals(original.getResult(), snapshot.getResult());
        assertEquals(original.isActualInterruption(), snapshot.isActualInterruption());
        assertNotSame(cause, snapshot.getCause());
        assertNotSame(suppressed, snapshot.getSuppressed()[0]);
        original.addSuppressed(new IOException("later"));
        cause.addSuppressed(new IOException("later cause"));
        suppressed.addSuppressed(new IOException("later callback"));
        assertEquals(1, snapshot.getSuppressed().length);
        assertEquals(1, snapshot.getCause().getSuppressed().length);
        assertEquals(0, snapshot.getSuppressed()[0].getSuppressed().length);
        assertEquals(0, snapshot.getCause().getSuppressed()[0].getSuppressed().length);
    }

    @Test
    void snapshotsOrdinaryFailuresWithCycles() {
        IOException original = new IOException("failure");
        IOException suppressed = new IOException("callback");
        original.addSuppressed(suppressed);
        suppressed.addSuppressed(original);
        ProxyException snapshot = assertInstanceOf(ProxyException.class, InterruptionSnapshot.copy(original));
        assertTrue(snapshot.getMessage().contains("failure"));
        assertEquals(1, snapshot.getSuppressed().length);
        assertEquals(0, snapshot.getSuppressed()[0].getSuppressed().length);
        original.addSuppressed(new IOException("later"));
        assertEquals(1, snapshot.getSuppressed().length);
    }
}
