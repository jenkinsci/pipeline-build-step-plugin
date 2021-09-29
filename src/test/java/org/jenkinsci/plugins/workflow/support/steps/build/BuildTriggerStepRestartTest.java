package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.model.Result;
import java.util.Arrays;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsSessionRule;

/**
 * @author Vivek Pandey
 */
public class BuildTriggerStepRestartTest extends Assert {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsSessionRule sessions = new JenkinsSessionRule();

    @Test
    public void restartBetweenJobs() throws Throwable {

        sessions.then(j -> {
                              j.jenkins.setNumExecutors(0);
                              FreeStyleProject p1 = j.createFreeStyleProject("test1");
                              WorkflowJob foo = j.createProject(WorkflowJob.class, "foo");
                              foo.setDefinition(new CpsFlowDefinition("build 'test1'", true));
                              WorkflowRun b = foo.scheduleBuild2(0).waitForStart();
                              j.waitForMessage("Scheduling project", b);
                              CpsFlowExecution e = (CpsFlowExecution) b.getExecutionPromise().get();
                              e.waitForSuspension();
                              assertFreeStyleProjectsInQueue(1, j);
                          }
        );

        sessions.then(j -> {
                assertFreeStyleProjectsInQueue(1, j);
                j.jenkins.setNumExecutors(2);
        });

        sessions.then(j -> {
                              j.waitUntilNoActivity();
                              FreeStyleProject p1 = j.jenkins.getItemByFullName("test1", FreeStyleProject.class);
                              FreeStyleBuild r = p1.getLastBuild();
                              assertNotNull(r);
                              assertEquals(1, r.number);
                              assertEquals(Result.SUCCESS, r.getResult());
                              assertFreeStyleProjectsInQueue(0, j);
                              WorkflowJob foo = j.jenkins.getItemByFullName("foo", WorkflowJob.class);
                              assertNotNull(foo);
                              WorkflowRun r2 = foo.getLastBuild();
                              assertNotNull(r2);
                              j.assertBuildStatusSuccess(r2);
        });

        sessions.then(j -> j.jenkins.getItemByFullName("test1", FreeStyleProject.class).getBuildByNumber(1).delete());
    }

    private static void assertFreeStyleProjectsInQueue(int count, JenkinsRule j) {
        Queue.Item[] items = j.jenkins.getQueue().getItems();
        int actual = 0;
        for (Queue.Item item : items) {
            if (item.task instanceof FreeStyleProject) {
                actual++;
            }
        }
        assertEquals(Arrays.toString(items), count, actual);
    }

}
