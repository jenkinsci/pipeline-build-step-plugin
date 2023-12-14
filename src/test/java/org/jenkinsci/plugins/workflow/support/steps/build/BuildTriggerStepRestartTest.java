package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsSessionRule;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * @author Vivek Pandey
 */
public class BuildTriggerStepRestartTest {

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

    @Test
    public void downstreamBuildAction() throws Throwable {
        sessions.then(j -> {
            FreeStyleProject downstream = j.createFreeStyleProject("downstream");
            downstream.setAssignedLabel(Label.parseExpression("agent"));
            WorkflowJob upstream = j.jenkins.createProject(WorkflowJob.class, "upstream");
            upstream.setDefinition(new CpsFlowDefinition("build(job: 'downstream', wait: false)", true));
            WorkflowRun upstreamRun = j.buildAndAssertSuccess(upstream);
            // Check action while the build is still in the queue.
            String buildStepId = BuildTriggerStepTest.findFirstNodeWithDescriptor(upstreamRun.getExecution(), BuildTriggerStep.DescriptorImpl.class).getId();
            FlowNode node = upstreamRun.getExecution().getNode(buildStepId);
            DownstreamBuildAction action = node.getAction(DownstreamBuildAction.class);
            assertThat(action.getJobFullName(), equalTo(downstream.getFullName()));
            assertThat(action.getBuildNumber(), nullValue());
            assertThat(action.getBuild(), nullValue());
            // Check again once the build has started.
            j.createOnlineSlave(Label.parseExpression("agent"));
            await().atMost(10, TimeUnit.SECONDS).until(() -> node.getAction(DownstreamBuildAction.class).getBuildNumber(), notNullValue());
            Run<?, ?> downstreamRun = downstream.getLastBuild();
            action = node.getAction(DownstreamBuildAction.class);
            assertThat(action.getJobFullName(), equalTo(downstream.getFullName()));
            assertThat(action.getBuildNumber(), equalTo(downstreamRun.getNumber()));
            assertThat(action.getBuild(), equalTo(downstreamRun));
            j.waitForCompletion(downstreamRun);
        });
        sessions.then(j -> {
            FreeStyleProject downstream = j.jenkins.getItemByFullName("downstream", FreeStyleProject.class);
            WorkflowJob upstream = j.jenkins.getItemByFullName("upstream", WorkflowJob.class);
            WorkflowRun upstreamRun = upstream.getLastBuild();
            String buildStepId = BuildTriggerStepTest.findFirstNodeWithDescriptor(upstreamRun.getExecution(), BuildTriggerStep.DescriptorImpl.class).getId();
            DownstreamBuildAction action = upstreamRun.getExecution().getNode(buildStepId).getAction(DownstreamBuildAction.class);
            assertThat(action.getJobFullName(), equalTo(downstream.getFullName()));
            assertThat(action.getBuildNumber(), equalTo(downstream.getLastBuild().getNumber()));
            assertThat(action.getBuild(), equalTo(downstream.getLastBuild()));
        });
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
