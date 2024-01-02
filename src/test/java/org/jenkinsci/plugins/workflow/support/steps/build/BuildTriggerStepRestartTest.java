package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.build.DownstreamBuildAction.DownstreamBuild;
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
    public void downstreamBuildActionUpstreamCompletesBeforeDownstreamStarts() throws Throwable {
        sessions.then(j -> {
            FreeStyleProject downstream = j.createFreeStyleProject("downstream");
            downstream.setAssignedLabel(Label.parseExpression("agent"));
            WorkflowJob upstream = j.jenkins.createProject(WorkflowJob.class, "upstream");
            upstream.setDefinition(new CpsFlowDefinition("build job: 'downstream', wait: false", true));
            WorkflowRun upstreamRun = j.buildAndAssertSuccess(upstream);
            // Check action while the build is still in the queue.
            FlowNode flowNode = BuildTriggerStepTest.findFirstNodeWithDescriptor(upstreamRun.getExecution(), BuildTriggerStep.DescriptorImpl.class);
            var downstreamBuildAction = upstreamRun.getAction(DownstreamBuildAction.class);
            var downstreamBuild = downstreamBuildAction.getDownstreamBuilds().get(0);
            assertThat(downstreamBuild.getFlowNodeId(), equalTo(flowNode.getId()));
            assertThat(downstreamBuild.getFlowNodeName(), equalTo(flowNode.getDisplayName()));
            assertThat(downstreamBuild.getJobFullName(), equalTo(downstream.getFullName()));
            assertThat(downstreamBuild.getBuildNumber(), nullValue());
            assertThat(downstreamBuild.getBuild(), nullValue());
            // Check again once the build has started.
            j.createOnlineSlave(Label.parseExpression("agent"));
            await().atMost(10, TimeUnit.SECONDS).until(() -> downstreamBuild.getBuildNumber(), notNullValue());
            Run<?, ?> downstreamRun = downstream.getLastBuild();
            assertThat(downstreamBuild.getJobFullName(), equalTo(downstream.getFullName()));
            assertThat(downstreamBuild.getBuildNumber(), equalTo(downstreamRun.getNumber()));
            assertThat(downstreamBuild.getBuild(), equalTo(downstreamRun));
            j.waitForCompletion(downstreamRun);
        });
        sessions.then(j -> {
            FreeStyleProject downstream = j.jenkins.getItemByFullName("downstream", FreeStyleProject.class);
            WorkflowJob upstream = j.jenkins.getItemByFullName("upstream", WorkflowJob.class);
            WorkflowRun upstreamRun = upstream.getLastBuild();
            FlowNode flowNode = BuildTriggerStepTest.findFirstNodeWithDescriptor(upstreamRun.getExecution(), BuildTriggerStep.DescriptorImpl.class);
            var downstreamBuildAction = upstreamRun.getAction(DownstreamBuildAction.class);
            var downstreamBuild = downstreamBuildAction.getDownstreamBuilds().get(0);
            assertThat(downstreamBuild.getFlowNodeId(), equalTo(flowNode.getId()));
            assertThat(downstreamBuild.getFlowNodeName(), equalTo(flowNode.getDisplayName()));
            assertThat(downstreamBuild.getJobFullName(), equalTo(downstream.getFullName()));
            assertThat(downstreamBuild.getBuildNumber(), equalTo(downstream.getLastBuild().getNumber()));
            assertThat(downstreamBuild.getBuild(), equalTo(downstream.getLastBuild()));
        });
    }

    @Test
    public void downstreamBuildActionMultipleBuilds() throws Throwable {
        sessions.then(j -> {
            FreeStyleProject downstream = j.createFreeStyleProject("downstream");
            WorkflowJob upstream = j.jenkins.createProject(WorkflowJob.class, "upstream");
            upstream.setDefinition(new CpsFlowDefinition("build 'downstream'; build 'downstream'", true));
            WorkflowRun upstreamRun = j.buildAndAssertSuccess(upstream);
            List<DownstreamBuild> downstreamBuilds = upstreamRun.getAction(DownstreamBuildAction.class).getDownstreamBuilds();
            await().atMost(10, TimeUnit.SECONDS).until(
                    () -> downstreamBuilds.stream().map(DownstreamBuild::getBuildNumber).filter(Objects::nonNull).count(),
                    equalTo(2L));
            for (Run<?, ?> downstreamRun : downstream.getBuilds()) {
                String nodeId = downstreamRun.getCause(BuildUpstreamCause.class).getNodeId();
                var downstreamBuild = downstreamBuilds.stream().filter(db -> db.getFlowNodeId().equals(nodeId)).findFirst().get();
                assertThat(downstreamBuild.getJobFullName(), equalTo(downstream.getFullName()));
                assertThat(downstreamBuild.getBuildNumber(), equalTo(downstreamRun.getNumber()));
                assertThat(downstreamBuild.getBuild(), equalTo(downstreamRun));
            }
        });
        sessions.then(j -> {
            FreeStyleProject downstream = j.jenkins.getItemByFullName("downstream", FreeStyleProject.class);
            WorkflowJob upstream = j.jenkins.getItemByFullName("upstream", WorkflowJob.class);
            WorkflowRun upstreamRun = upstream.getLastBuild();
            List<DownstreamBuild> downstreamBuilds = upstreamRun.getAction(DownstreamBuildAction.class).getDownstreamBuilds();
            for (Run<?, ?> downstreamRun : downstream.getBuilds()) {
                String nodeId = downstreamRun.getCause(BuildUpstreamCause.class).getNodeId();
                var downstreamBuild = downstreamBuilds.stream().filter(db -> db.getFlowNodeId().equals(nodeId)).findFirst().get();
                assertThat(downstreamBuild.getJobFullName(), equalTo(downstream.getFullName()));
                assertThat(downstreamBuild.getBuildNumber(), equalTo(downstreamRun.getNumber()));
                assertThat(downstreamBuild.getBuild(), equalTo(downstreamRun));
            }
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
