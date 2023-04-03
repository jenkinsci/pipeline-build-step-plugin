package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.Builder;
import hudson.util.DescribableList;
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
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.UnstableBuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class WaitForBuildStepTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public LoggerRule logging = new LoggerRule();

    @Test public void waitForBuild() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        DescribableList<Builder, Descriptor<Builder>> buildersList = ds.getBuildersList();
        buildersList.add(new SleepBuilder(500));
        buildersList.add(new FailureBuilder());
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "def completeDs = waitForBuild runId: dsRunId\n" +
            "echo \"'ds' completed with status ${completeDs.getResult()}\"", true));
        j.assertLogContains("'ds' completed with status FAILURE", j.buildAndAssertSuccess(us));
    }

    @Test public void waitForBuildPropagte() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        DescribableList<Builder, Descriptor<Builder>> buildersList = ds.getBuildersList();
        buildersList.add(new SleepBuilder(500));
        buildersList.add(new FailureBuilder());
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "waitForBuild runId: dsRunId, propagate: true", true));
        j.assertLogContains("completed with status FAILURE", j.buildAndAssertStatus(Result.FAILURE, us));
    }

    @SuppressWarnings("rawtypes")
    @Test public void waitForBuildAlreadyComplete() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        ds.getBuildersList().add(new FailureBuilder());
        Run ds1 = ds.scheduleBuild2(0).waitForStart();
        assertEquals(1, ds1.getNumber());
        j.waitForCompletion(ds1);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("waitForBuild runId: 'ds#1'", true));
        j.assertLogContains("is already complete", j.buildAndAssertSuccess(us));
    }

    @Issue("JENKINS-70983")
    @Test public void waitForUnstableBuildWithWarningAction() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        DescribableList<Builder, Descriptor<Builder>> buildersList = ds.getBuildersList();
        buildersList.add(new SleepBuilder(500));
        buildersList.add(new UnstableBuilder());
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "def completeDs = waitForBuild runId: dsRunId\n" +
            "echo \"'ds' completed with status ${completeDs.getResult()}\"", true));
        j.assertLogContains("'ds' completed with status UNSTABLE", j.buildAndAssertSuccess(us));
        WorkflowRun lastUpstreamRun = us.getLastBuild();
        FlowNode buildTriggerNode = findFirstNodeWithDescriptor(lastUpstreamRun.getExecution(), WaitForBuildStep.DescriptorImpl.class);
        WarningAction action = buildTriggerNode.getAction(WarningAction.class);
        assertNotNull(action);
        assertEquals(action.getResult(), Result.UNSTABLE);
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
}
