package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.queue.QueueTaskFuture;

import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
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
        createWaitingDownStreamJob(dsResult);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "def completeDs = waitForBuild runId: dsRunId\n" +
            "echo \"'ds' completed with status ${completeDs.getResult()}\"", true));
        j.assertLogContains("'ds' completed with status " + dsResult.toString(), j.buildAndAssertSuccess(us));
    }

    @Test public void waitForBuildPropagte() throws Exception {
        Result dsResult = Result.FAILURE;
        createWaitingDownStreamJob(dsResult);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "waitForBuild runId: dsRunId, propagate: true", true));
        j.assertLogContains("completed with status " + dsResult.toString(), j.buildAndAssertStatus(dsResult, us));
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
        createWaitingDownStreamJob(dsResult);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "try {\n" +
            "    waitForBuild runId: dsRunId, propagate: true\n" +
            "} finally {\n" +
            "    echo \"'ds' completed with status ${ds.getResult()}\"\n" +
            "}", true));
        j.assertLogContains("'ds' completed with status " + dsResult.toString(), j.buildAndAssertStatus(dsResult, us));
        WorkflowRun lastUpstreamRun = us.getLastBuild();
        FlowNode buildTriggerNode = findFirstNodeWithDescriptor(lastUpstreamRun.getExecution(), WaitForBuildStep.DescriptorImpl.class);
        WarningAction action = buildTriggerNode.getAction(WarningAction.class);
        assertNotNull(action);
        assertEquals(action.getResult(), Result.UNSTABLE);
    }

    @Issue("JENKINS-71961")
    @Test public void abortBuild() throws Exception {
        WorkflowJob ds = createWaitingDownStreamJob(Result.SUCCESS, true);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "def completeDs = waitForBuild runId: dsRunId, propagate: true\n" +
            "echo \"'ds' completed with status ${completeDs.getResult()}\"", true));

        QueueTaskFuture<WorkflowRun> q = us.scheduleBuild2(0);
        WorkflowRun us1 = q.getStartCondition().get();

        // wait for the downstream job to start and to be waited on by the upstream job
        WorkflowRun ds1 = null;
        while(true) {
            ds1 = ds.getBuildByNumber(1);
            if (ds1 != null) {
                if (ds1.getAction(WaitForBuildAction.class) != null) {
                    break;
                }
            }
            Thread.sleep(10);
        }

        // Abort the downstream build
        ds1.getExecutor().interrupt();

        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(ds1));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(us1));
    }

    @Issue("JENKINS-71961")
    @Test public void interruptFlow() throws Exception {
        WorkflowJob ds = createWaitingDownStreamJob(Result.SUCCESS, true);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "def completeDs = waitForBuild runId: dsRunId, propagate: true\n" +
            "echo \"'ds' completed with status ${completeDs.getResult()}\"", true));

        QueueTaskFuture<WorkflowRun> q = us.scheduleBuild2(0);
        WorkflowRun us1 = q.getStartCondition().get();

        // wait for the downstream job to start and to be waited on by the upstream job
        WorkflowRun ds1 = null;
        while(true) {
            ds1 = ds.getBuildByNumber(1);
            if (ds1 != null) {
                if (ds1.getAction(WaitForBuildAction.class) != null) {
                    break;
                }
            }
            Thread.sleep(10);
        }

        // Abort the upstream build
        us1.doStop();

        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(ds1));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(us1));
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

    private WorkflowJob createWaitingDownStreamJob(Result result, boolean indefiniteWait) throws Exception {
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition(
            "import org.jenkinsci.plugins.workflow.support.steps.build.WaitForBuildAction\n" +
            "@NonCPS\n" + 
            "boolean hasWaitForBuildAction() {\n" +
            "    return currentBuild.getRawBuild().getAction(WaitForBuildAction.class) != null\n" +
            "}\n" +
            "while(!hasWaitForBuildAction()) {\n" +
            "    sleep(time: 100, unit: 'MILLISECONDS')\n" +
            "}\n" +
            "while(" + String.valueOf(indefiniteWait) + ") {\n" +
            "    sleep(time: 100, unit: 'MILLISECONDS')\n" +
            "}\n" +
            "catchError(buildResult: '" + result.toString() + "') {\n" +
            "    error('')\n" +
            "}", false));
        return ds;
        
    }

    private WorkflowJob createWaitingDownStreamJob(Result result) throws Exception {
        return createWaitingDownStreamJob(result, false);
    }

}
