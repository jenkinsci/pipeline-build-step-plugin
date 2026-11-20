package org.jenkinsci.plugins.workflow.support.steps.build;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.ProxyException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ConcurrentModificationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jenkins.model.InterruptedBuildAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.properties.DurabilityHintJobProperty;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A concurrent change to an interruption must not leave an unreadable build record. */
@WithJenkins
class BuildRecordCorruptionTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    @ParameterizedTest(name = "{0}, retainCancellationCause={1}")
    @CsvSource({
        "PERFORMANCE_OPTIMIZED, false",
        "PERFORMANCE_OPTIMIZED, true",
        "MAX_SURVIVABILITY, false"
    })
    void preservesBuildRecordAfterConcurrentMutation(
            FlowDurabilityHint hint, boolean retainCancellationCause, JenkinsRule j) throws Exception {
        j.jenkins.setQuietPeriod(0);
        WorkflowJob downstream = j.createProject(WorkflowJob.class, "downstream");
        downstream.addProperty(new DurabilityHintJobProperty(hint));
        downstream.setDefinition(new CpsFlowDefinition("semaphore 'downstream'", true));
        WorkflowJob upstream = j.createProject(WorkflowJob.class, "upstream");
        upstream.setDefinition(new CpsFlowDefinition("""
                parallel first: { build job: 'downstream', wait: true, propagate: false },
                         second: { build job: 'downstream', wait: true, propagate: false },
                         failure: { semaphore 'fail'; error 'fail fast' }, failFast: true
                """, true));
        WorkflowRun parent = upstream.scheduleBuild2(0).getStartCondition().get();
        SemaphoreStep.waitForStart("downstream/1", null);
        SemaphoreStep.waitForStart("downstream/2", null);
        WorkflowRun first = downstream.getBuildByNumber(1);
        WorkflowRun second = downstream.getBuildByNumber(2);
        BuildTriggerAction firstAction = first.getAction(BuildTriggerAction.class);
        BuildTriggerAction.Trigger firstTrigger = BuildTriggerAction.triggersFor(first).iterator().next();
        BuildTriggerAction.Trigger secondTrigger = BuildTriggerAction.triggersFor(second).iterator().next();
        SemaphoreStep.success("fail/1", null);
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(parent));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(first));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(second));
        assertInstanceOf(FlowInterruptedException.class, firstTrigger.interruption);
        assertSame(firstTrigger.interruption, secondTrigger.interruption);
        assertFalse(firstTrigger.propagate);
        assertEquals(hint, first.getExecution().getDurabilityHint());

        // Finalization removes the actions. Restore the captured graph to isolate a save that
        // overlaps mutation, without relying on the timing of downstream finalization.
        if (!retainCancellationCause) {
            // Exercise Trigger.interruption directly. The other case keeps the cancellation cause,
            // whose throwable XStream encounters first through InterruptedBuildAction.
            first.removeActions(InterruptedBuildAction.class);
        }
        first.addAction(firstAction);
        first.save();
        var file = first.getRootDir().toPath().resolve("build.xml");
        byte[] before = Files.readAllBytes(file);
        SerializationGate marker = new SerializationGate();
        firstTrigger.interruption.addSuppressed(marker);
        PausingConverter converter = new PausingConverter(marker);
        Run.XSTREAM2.registerConverter(converter, XStream.PRIORITY_VERY_HIGH);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var saving = executor.submit(() -> {
                try {
                    first.save();
                    return null;
                } catch (IOException | RuntimeException e) {
                    return e;
                }
            });
            assertTrue(converter.entered.await(30, TimeUnit.SECONDS), "save reached suppressedExceptions iterator");
            try {
                // This is the observed callback failure source. Explicitly append it at the
                // callback boundary: scheduling a complete callback race is a separate concern.
                IOException missingThread = assertThrows(IOException.class,
                        () -> secondTrigger.context.get(TaskListener.class));
                assertTrue(missingThread.getMessage().contains("cannot find current thread"), missingThread.toString());
                secondTrigger.interruption.addSuppressed(missingThread);
            } finally {
                converter.resume.countDown();
            }
            Throwable saveFailure = saving.get(30, TimeUnit.SECONDS);
            if (saveFailure != null) {
                Throwable rootCause = saveFailure;
                while (rootCause.getCause() != null) {
                    rootCause = rootCause.getCause();
                }
                assertInstanceOf(ConcurrentModificationException.class, rootCause);
                // A failed write must leave the previous valid record intact.
                // Reload first so the regression reports the user-visible failure.
                assertDoesNotThrow(first::reload,
                        "Build record must remain readable after a failed save (" + hint + ")");
                assertArrayEquals(before, Files.readAllBytes(file));
            } else {
                // Also accept implementations that safely snapshot the mutable exception graph.
                assertDoesNotThrow(first::reload);
            }
            assertEquals(Result.ABORTED, first.getResult());
            FlowInterruptedException original = (FlowInterruptedException) firstTrigger.interruption;
            FlowInterruptedException restored = assertInstanceOf(FlowInterruptedException.class,
                    BuildTriggerAction.triggersFor(first).iterator().next().interruption);
            assertEquals(original.getResult(), restored.getResult());
            assertEquals(original.isActualInterruption(), restored.isActualInterruption());
            assertEquals(original.getCauses().get(0).getShortDescription(),
                    restored.getCauses().get(0).getShortDescription());
        } finally {
            converter.resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "save thread terminated");
        }
    }

    private static final class SerializationGate extends Exception {}

    /** Pauses inside a real collection iterator; never throws or changes serialization behavior. */
    private static final class PausingConverter implements Converter {
        private final SerializationGate marker;
        private final Converter markerDelegate;
        private final Converter proxyDelegate;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        PausingConverter(SerializationGate marker) {
            this.marker = marker;
            markerDelegate = Run.XSTREAM2.getConverterLookup().lookupConverterForType(SerializationGate.class);
            proxyDelegate = Run.XSTREAM2.getConverterLookup().lookupConverterForType(ProxyException.class);
        }

        @Override public boolean canConvert(Class type) {
            return type == SerializationGate.class || type == ProxyException.class;
        }

        @Override public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
            if (source == marker || source instanceof ProxyException proxy && proxy.getMessage().equals(marker.toString())) {
                entered.countDown();
                try {
                    if (!resume.await(30, TimeUnit.SECONDS)) {
                        throw new AssertionError("serialization was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            (source instanceof ProxyException ? proxyDelegate : markerDelegate).marshal(source, writer, context);
        }

        @Override public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
            return (context.getRequiredType() == ProxyException.class ? proxyDelegate : markerDelegate)
                    .unmarshal(reader, context);
        }
    }
}
