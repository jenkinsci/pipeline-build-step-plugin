package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;

import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@WithJenkins
class WaitForBuildStepTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    @SuppressWarnings("unused")
    private final LogRecorder logging = new LogRecorder();

    private JenkinsRule r;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void waitForBuild() throws Exception {
        Result dsResult = Result.FAILURE;
        WorkflowJob ds = createWaitingDownStreamJob("wait", dsResult);
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        def completeDs = waitForBuild runId: dsRunId
                        echo "'ds' completed with status ${completeDs.getResult()}\"""", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();

        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        // signal the downstream run to complete after it has been waited on
        WorkflowRun dsRun = ds.getBuildByNumber(1);
        SemaphoreStep.waitForStart("wait/1", dsRun);
        waitForWaitForBuildAction(dsRun);
        SemaphoreStep.success("wait/1", true);

        // assert upstream build status
        WorkflowRun completedUsRun = r.waitForCompletion(usRun);
        r.assertBuildStatusSuccess(completedUsRun);
        r.assertLogContains("'ds' completed with status " + dsResult, completedUsRun);
    }

    @Test
    void waitForBuildPropagate() throws Exception {
        Result dsResult = Result.FAILURE;
        WorkflowJob ds = createWaitingDownStreamJob("wait", dsResult);
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        waitForBuild runId: dsRunId, propagate: true""", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        // signal the downstream run to complete after it has been waited on
        WorkflowRun dsRun = ds.getBuildByNumber(1);
        waitForWaitForBuildAction(dsRun);
        SemaphoreStep.success("wait/1", true);

        // assert upstream build status
        WorkflowRun completedUsRun = r.waitForCompletion(usRun);
        r.assertBuildStatus(dsResult, completedUsRun);
        r.assertLogContains("completed with status " + dsResult, completedUsRun);
    }

    @SuppressWarnings("rawtypes")
    @Test
    void waitForBuildAlreadyCompleteFailure() throws Exception {
        FreeStyleProject ds = r.createFreeStyleProject("ds");
        ds.getBuildersList().add(new FailureBuilder());
        Run ds1 = ds.scheduleBuild2(0).waitForStart();
        assertEquals(1, ds1.getNumber());
        r.waitForCompletion(ds1);
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("waitForBuild runId: 'ds#1'", true));
        Result dsResult = Result.FAILURE;
        r.assertLogContains("already completed: "+ dsResult, r.buildAndAssertSuccess(us));
    }

    @Issue("JENKINS-71342")
    @SuppressWarnings("rawtypes")
    @Test void waitForBuildPropagateAlreadyCompleteFailure() throws Exception {
        FreeStyleProject ds = r.createFreeStyleProject("ds");
        ds.getBuildersList().add(new FailureBuilder());
        Run ds1 = ds.scheduleBuild2(0).waitForStart();
        assertEquals(1, ds1.getNumber());
        r.waitForCompletion(ds1);
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("waitForBuild runId: 'ds#1', propagate: true", true));
        Result dsResult = Result.FAILURE;
        r.assertLogContains("already completed: "+ dsResult, r.buildAndAssertStatus(dsResult, us));
    }

    @Issue("JENKINS-70983")
    @Test
    void waitForUnstableBuildWithWarningAction() throws Exception {
        Result dsResult = Result.UNSTABLE;
        WorkflowJob ds = createWaitingDownStreamJob("wait", dsResult);
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        try {
                            waitForBuild runId: dsRunId, propagate: true
                        } finally {
                            echo "'ds' completed with status ${ds.getResult()}"
                        }""", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        // signal the downstream run to complete after it has been waited on
        WorkflowRun dsRun = ds.getBuildByNumber(1);
        waitForWaitForBuildAction(dsRun);
        SemaphoreStep.success("wait/1", true);

        // assert upstream build status
        WorkflowRun completedUsRun = r.waitForCompletion(usRun);
        r.assertBuildStatus(dsResult, completedUsRun);
        r.assertLogContains("'ds' completed with status " + dsResult, completedUsRun);

        FlowNode buildTriggerNode = findFirstNodeWithDescriptor(completedUsRun.getExecution(), WaitForBuildStep.DescriptorImpl.class);
        WarningAction action = buildTriggerNode.getAction(WarningAction.class);
        assertNotNull(action);
        assertEquals(Result.UNSTABLE, action.getResult());
    }

    @Issue("JENKINS-71961")
    @Test
    void abortBuild() throws Exception {
        WorkflowJob ds = createWaitingDownStreamJob("wait", Result.SUCCESS);
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        def completeDs = waitForBuild runId: dsRunId, propagate: true
                        echo "'ds' completed with status ${completeDs.getResult()}\"""", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        WorkflowRun dsRun = ds.getBuildByNumber(1);
        SemaphoreStep.waitForStart("wait/1", dsRun);
        waitForWaitForBuildAction(dsRun);

        // Abort the downstream build
        dsRun.getExecutor().interrupt();

        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(dsRun));
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(usRun));
    }

    @Issue("JENKINS-71961")
    @Test
    void interruptFlowPropagateAbort() throws Exception {
        WorkflowJob ds = createWaitingDownStreamJob("wait", Result.SUCCESS);
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        def completeDs = waitForBuild runId: dsRunId, propagate: true, propagateAbort: true
                        echo "'ds' completed with status ${completeDs.getResult()}\"""", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        WorkflowRun dsRun = ds.getBuildByNumber(1);
        SemaphoreStep.waitForStart("wait/1", dsRun);
        waitForWaitForBuildAction(dsRun);

        // Abort the upstream build
        usRun.doStop();

        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(dsRun));
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(usRun));
    }

    @Issue("JENKINS-71961")
    @Test
    void interruptFlowNoPropagateAbort() throws Exception {
        WorkflowJob ds = createWaitingDownStreamJob("wait", Result.SUCCESS);
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        def completeDs = waitForBuild runId: dsRunId, propagate: true, propagateAbort: false
                        echo "'ds' completed with status ${completeDs.getResult()}\"""", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        WorkflowRun dsRun = ds.getBuildByNumber(1);
        SemaphoreStep.waitForStart("wait/1", dsRun);
        waitForWaitForBuildAction(dsRun);

        // Abort the upstream build
        usRun.doStop();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(usRun));

        // Allow the downstream to complete
        SemaphoreStep.success("wait/1", true);
        r.assertBuildStatus(Result.SUCCESS, r.waitForCompletion(dsRun));
    }

    private static FlowNode findFirstNodeWithDescriptor(FlowExecution execution, Class<WaitForBuildStep.DescriptorImpl> cls) {
        for (FlowNode node : new FlowGraphWalker(execution)) {
            if (node instanceof StepAtomNode stepAtomNode) {
                if (cls.isInstance(stepAtomNode.getDescriptor())) {
                    return stepAtomNode;
                }
            }
        }
        return null;
    }

    private WorkflowJob createWaitingDownStreamJob(String semaphoreName, Result result) throws Exception {
        WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition(
            "semaphore('" + semaphoreName + "')\n" +
            "catchError(buildResult: '" + result.toString() + "') {\n" +
            "    error('')\n" +
            "}", false));
        return ds;
    }

    private void waitForWaitForBuildAction(WorkflowRun r) {
       await().until(() -> r.getAction(WaitForBuildAction.class) != null);
    }

}
