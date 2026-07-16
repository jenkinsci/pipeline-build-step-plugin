package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.Result;
import hudson.tasks.LogRotator;
import java.util.logging.Level;
import jenkins.model.BuildDiscarderProperty;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.LogRecorder;
import static org.jvnet.hudson.test.LogRecorder.recorded;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.any;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

class WaitForBuildStepRestartTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void upstreamBuildDeletion() throws Throwable {
        waitForBuildDeletion(1, 2);
    }

    @Test
    void downstreamBuildDeletion() throws Throwable {
        waitForBuildDeletion(2, 1);
    }

    private void waitForBuildDeletion(int upstreamNumToKeep, int downstreamNumToKeep) throws Throwable {
        sessions.then(r -> {
            r.jenkins.setQuietPeriod(0);
            var ds = r.jenkins.createProject(WorkflowJob.class, "ds");
            ds.setDefinition(new CpsFlowDefinition("echo 'ran'", true));
            ds.addProperty(new BuildDiscarderProperty(new LogRotator(-1, downstreamNumToKeep, -1, -1)));
            var us = r.jenkins.createProject(WorkflowJob.class, "us");
            us.setDefinition(new CpsFlowDefinition("""
                    def dsRun = build job: 'ds', waitForStart: true
                    def dsRunId = "${dsRun.getFullProjectName()}#${dsRun.getNumber()}"
                    waitForBuild runId: dsRunId
                    """, true));
            us.addProperty(new BuildDiscarderProperty(new LogRotator(-1, upstreamNumToKeep, -1, -1)));
            r.assertBuildStatus(Result.SUCCESS, r.buildAndAssertSuccess(us));
            r.assertBuildStatus(Result.SUCCESS, r.buildAndAssertSuccess(us));
        });
        sessions.then(r -> {
            try (var logging = new LogRecorder().record("org.jenkinsci.plugins.workflow", Level.WARNING).capture(100)) {
                r.buildAndAssertSuccess(r.jenkins.getItemByFullName("us", WorkflowJob.class));
                assertThat(logging, not(recorded(Level.WARNING, anyOf(any(String.class), nullValue()))));
            }
        });
    }
}
