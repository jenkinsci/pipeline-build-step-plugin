package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;

import static org.junit.Assert.assertEquals;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;

public class WaitForBuildStepTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public LoggerRule logging = new LoggerRule();

    @Test public void waitForBuild() throws Exception {
        j.createFreeStyleProject("ds").getBuildersList().add(new FailureBuilder());
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build job: 'ds', waitForStart: true\n" +
            "def dsRunId = \"${ds.getFullProjectName()}#${ds.getNumber()}\"\n" +
            "def completeDs = waitForBuild runId: dsRunId\n" +
            "echo \"'ds' completed with status ${completeDs.getResult()}\"", true));
        j.assertLogContains("'ds' completed with status FAILURE", j.buildAndAssertSuccess(us));
    }

    @Test public void waitForBuildPropagte() throws Exception {
        j.createFreeStyleProject("ds").getBuildersList().add(new FailureBuilder());
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
}
