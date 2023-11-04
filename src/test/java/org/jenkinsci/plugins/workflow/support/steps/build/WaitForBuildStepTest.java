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
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class WaitForBuildStepTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public LoggerRule logging = new LoggerRule();

    @Test public void waitForBuild() throws Exception {
        Result dsResult = Result.FAILURE;
        WorkflowJob ds = createWaitingDownStreamJob("wait", dsResult);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "semaphore 'scheduled'\n" + 
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "def completeDs = waitForBuild runId: dsRunId\n" +
            "echo \"'ds' completed with status ${completeDs.getResult()}\"", true));

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
        WorkflowRun completedUsRun = j.waitForCompletion(usRun);
        j.assertBuildStatusSuccess(completedUsRun);
        j.assertLogContains("'ds' completed with status " + dsResult.toString(), completedUsRun);
    }

    @Test public void waitForBuildPropagte() throws Exception {
        Result dsResult = Result.FAILURE;
        WorkflowJob ds = createWaitingDownStreamJob("wait", dsResult);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "semaphore 'scheduled'\n" + 
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "waitForBuild runId: dsRunId, propagate: true", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        // signal the downstream run to complete after it's been waited on
        WorkflowRun dsRun = ds.getLastBuild();
        waitForWaitForBuildAction(dsRun);
        SemaphoreStep.success("wait/1", true);

        // assert upstream build status
        WorkflowRun completedUsRun = j.waitForCompletion(usRun);
        j.assertBuildStatus(dsResult, completedUsRun);
        j.assertLogContains("completed with status " + dsResult.toString(), completedUsRun);
    }

    @SuppressWarnings("rawtypes")
    @Test public void waitForBuildAlreadyCompleteFailure() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        ds.getBuildersList().add(new FailureBuilder());
        Run ds1 = ds.scheduleBuild2(0).waitForStart();
        assertEquals(1, ds1.getNumber());
        j.waitForCompletion(ds1);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("waitForBuild runId: 'ds#1'", true));
        Result dsResult = Result.FAILURE;
        j.assertLogContains("already completed: "+ dsResult.toString(), j.buildAndAssertSuccess(us));
    }

    @Issue("JENKINS-71342")
    @SuppressWarnings("rawtypes")
    @Test public void waitForBuildPropagateAlreadyCompleteFailure() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        ds.getBuildersList().add(new FailureBuilder());
        Run ds1 = ds.scheduleBuild2(0).waitForStart();
        assertEquals(1, ds1.getNumber());
        j.waitForCompletion(ds1);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("waitForBuild runId: 'ds#1', propagate: true", true));
        Result dsResult = Result.FAILURE;
        j.assertLogContains("already completed: "+ dsResult.toString(), j.buildAndAssertStatus(dsResult, us));
    }

    @Issue("JENKINS-70983")
    @Test public void waitForUnstableBuildWithWarningAction() throws Exception {
        Result dsResult = Result.UNSTABLE;
        WorkflowJob ds = createWaitingDownStreamJob("wait", dsResult);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "semaphore 'scheduled'\n" +
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "try {\n" +
            "    waitForBuild runId: dsRunId, propagate: true\n" +
            "} finally {\n" +
            "    echo \"'ds' completed with status ${ds.getResult()}\"\n" +
            "}", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        // signal the downstream run to complete after being waited on
        WorkflowRun dsRun = ds.getLastBuild();
        waitForWaitForBuildAction(dsRun);
        SemaphoreStep.success("wait/1", true);

        // assert upstream build status
        WorkflowRun completedUsRun = j.waitForCompletion(usRun);
        j.assertBuildStatus(dsResult, completedUsRun);
        j.assertLogContains("'ds' completed with status " + dsResult.toString(), completedUsRun);

        FlowNode buildTriggerNode = findFirstNodeWithDescriptor(completedUsRun.getExecution(), WaitForBuildStep.DescriptorImpl.class);
        WarningAction action = buildTriggerNode.getAction(WarningAction.class);
        assertNotNull(action);
        assertEquals(action.getResult(), Result.UNSTABLE);
    }

    @Issue("JENKINS-71961")
    @Test public void abortBuild() throws Exception {
        WorkflowJob ds = createWaitingDownStreamJob("wait", Result.SUCCESS);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "semaphore 'scheduled'\n" + 
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "def completeDs = waitForBuild runId: dsRunId, propagate: true\n" +
            "echo \"'ds' completed with status ${completeDs.getResult()}\"", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        WorkflowRun dsRun = ds.getBuildByNumber(1);
        waitForWaitForBuildAction(dsRun);

        // Abort the downstream build
        dsRun.getExecutor().interrupt();

        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(dsRun));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(usRun));
    }

    @Issue("JENKINS-71961")
    @Test public void interruptFlowPropagateAbort() throws Exception {
        WorkflowJob ds = createWaitingDownStreamJob("wait", Result.SUCCESS);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "semaphore 'scheduled'\n" +
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "def completeDs = waitForBuild runId: dsRunId, propagate: true, propagateAbort: true\n" +
            "echo \"'ds' completed with status ${completeDs.getResult()}\"", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        WorkflowRun dsRun = ds.getBuildByNumber(1);
        waitForWaitForBuildAction(dsRun);

        // Abort the upstream build
        usRun.doStop();

        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(dsRun));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(usRun));
    }

    @Issue("JENKINS-71961")
    @Test public void interruptFlowNoPropagateAbort() throws Exception {
        WorkflowJob ds = createWaitingDownStreamJob("wait", Result.SUCCESS);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "semaphore 'scheduled'\n" +
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "def completeDs = waitForBuild runId: dsRunId, propagate: true, propagateAbort: false\n" +
            "echo \"'ds' completed with status ${completeDs.getResult()}\"", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        WorkflowRun dsRun = ds.getBuildByNumber(1);
        waitForWaitForBuildAction(dsRun);

        // Abort the upstream build
        usRun.doStop();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(usRun));

        // Allow the downstream to complete
        SemaphoreStep.success("wait/1", true);
        j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(dsRun));
    }

    private static FlowNode findFirstNodeWithDescriptor(FlowExecution execution, Class<WaitForBuildStep.DescriptorImpl> cls) {
        for (FlowNode node : new FlowGraphWalker(execution)) {
            if (node instanceof StepAtomNode) {
                StepAtomNode stepAtomNode = (StepAtomNode) node;
                if (cls.isInstance(stepAtomNode.getDescriptor())) {
                    return stepAtomNode;
                }
            }
        }
        return null;
    }

    private WorkflowJob createWaitingDownStreamJob(String semaphoreName, Result result) throws Exception {
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition(
            "semaphore('" + semaphoreName + "')\n" +
            "catchError(buildResult: '" + result.toString() + "') {\n" +
            "    error('')\n" +
            "}", false));
        return ds;
    }

    private void waitForWaitForBuildAction(WorkflowRun r) throws Exception {
        while(true) {
            if (r.getAction(WaitForBuildAction.class) != null) {
                break;
            }
            Thread.sleep(10);
        }
    }

}
