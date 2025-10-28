package org.jenkinsci.plugins.workflow.support.steps.build;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsParameterDefinition;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.Action;
import hudson.model.BooleanParameterDefinition;
import hudson.model.BooleanParameterValue;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.util.StreamTaskListener;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import jenkins.branch.MultiBranchProjectFactory;
import jenkins.branch.MultiBranchProjectFactoryDescriptor;
import jenkins.branch.OrganizationFolder;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMSource;
import jenkins.scm.impl.mock.MockSCMController;
import jenkins.scm.impl.mock.MockSCMDiscoverBranches;
import jenkins.scm.impl.mock.MockSCMNavigator;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.apache.commons.lang.StringUtils;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.SnippetizerTester;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.CaptureEnvironmentBuilder;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.MockQueueItemAuthenticator;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.UnstableBuilder;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.recipes.LocalData;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@WithJenkins
class BuildTriggerStepTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    private final LogRecorder logging = new LogRecorder();

    private JenkinsRule r;

    @BeforeEach
    void beforeEach(JenkinsRule rule) throws Exception {
        r = rule;
        r.jenkins.setQuietPeriod(0);
    }

    @Issue("JENKINS-25851")
    @Test
    void buildTopLevelProject() throws Exception {
        FreeStyleProject ds = r.createFreeStyleProject("ds");
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build 'ds'\n" +
            "echo \"ds.result=${ds.result} ds.number=${ds.number}\"", true));
        r.assertLogContains("ds.result=SUCCESS ds.number=1", r.buildAndAssertSuccess(us));
        // TODO JENKINS-28673 assert no warnings, as in StartupTest.noWarnings
        // (but first need to deal with `WARNING: Failed to instantiate optional component org.jenkinsci.plugins.workflow.steps.scm.SubversionStep$DescriptorImpl; skipping`)
        ds.getBuildByNumber(1).delete();
    }

    @Issue("JENKINS-25851")
    @Test
    void failingBuild() throws Exception {
        r.createFreeStyleProject("ds").getBuildersList().add(new FailureBuilder());
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        r.assertBuildStatus(Result.FAILURE, us.scheduleBuild2(0));
        us.setDefinition(new CpsFlowDefinition("echo \"ds.result=${build(job: 'ds', propagate: false).result}\"", true));
        r.assertLogContains("ds.result=FAILURE", r.buildAndAssertSuccess(us));
    }

    @Issue("JENKINS-70623")
    @Test
    void failingBuildWithWarningAction() throws Exception {
        r.createFreeStyleProject("ds").getBuildersList().add(new FailureBuilder());
        WorkflowJob upstream = r.jenkins.createProject(WorkflowJob.class, "us");
        upstream.setDefinition(new CpsFlowDefinition("build(job: 'ds')", true));
        WorkflowRun lastUpstream = r.buildAndAssertStatus(Result.FAILURE, upstream);
        r.assertLogContains("completed: FAILURE", lastUpstream);
        FlowNode buildTriggerNode = findFirstNodeWithDescriptor(lastUpstream.getExecution(), BuildTriggerStep.DescriptorImpl.class);
        WarningAction action = buildTriggerNode.getAction(WarningAction.class);
        assertNotNull(action);
        assertEquals(Result.FAILURE, action.getResult());
    }

    @Issue("JENKINS-70623")
    @Test
    void unstableBuildWithWarningAction() throws Exception {
        r.createFreeStyleProject("ds").getBuildersList().add(new UnstableBuilder());
        WorkflowJob upstream = r.jenkins.createProject(WorkflowJob.class, "us");
        upstream.setDefinition(new CpsFlowDefinition("build(job: 'ds')", true));
        WorkflowRun lastUpstream = r.buildAndAssertStatus(Result.UNSTABLE, upstream);
        r.assertLogContains("completed: UNSTABLE", lastUpstream);
        FlowNode buildTriggerNode = findFirstNodeWithDescriptor(lastUpstream.getExecution(), BuildTriggerStep.DescriptorImpl.class);
        WarningAction action = buildTriggerNode.getAction(WarningAction.class);
        assertNotNull(action);
        assertEquals(Result.UNSTABLE, action.getResult());
    }

    @Issue("JENKINS-70623")
    @Test
    void successBuildNoWarningAction() throws Exception {
        r.createFreeStyleProject("ds");
        WorkflowJob upstream = r.jenkins.createProject(WorkflowJob.class, "us");
        upstream.setDefinition(new CpsFlowDefinition("build(job: 'ds')", true));
        WorkflowRun lastUpstream = r.buildAndAssertSuccess(upstream);
        r.assertLogContains("completed: SUCCESS", lastUpstream);
        FlowNode buildTriggerNode = findFirstNodeWithDescriptor(lastUpstream.getExecution(), BuildTriggerStep.DescriptorImpl.class);
        WarningAction action = buildTriggerNode.getAction(WarningAction.class);
        assertNull(action);
    }

    @Issue("JENKINS-60995")
    @Test
    void upstreamCause() throws Exception {
        FreeStyleProject downstream = r.createFreeStyleProject("downstream");
        WorkflowJob upstream = r.jenkins.createProject(WorkflowJob.class, "upstream");

        upstream.setDefinition(new CpsFlowDefinition("build 'downstream'", true));
        int numberOfUpstreamBuilds = 3;
        for (int i = 0; i < numberOfUpstreamBuilds; i++) {
            upstream.scheduleBuild2(0);
            // Wait a bit before scheduling next one
            Thread.sleep(100);
        }
        for (WorkflowRun upstreamRun : upstream.getBuilds()) {
            r.waitForCompletion(upstreamRun);
            r.assertBuildStatus(Result.SUCCESS, upstreamRun);
        }
        // Wait for all downstream builds to complete
        r.waitUntilNoActivity();

        List<BuildUpstreamCause> upstreamCauses = new ArrayList<>();
        for (Run<FreeStyleProject, FreeStyleBuild> downstreamRun : downstream.getBuilds()) {
            upstreamCauses.addAll(downstreamRun.getCauses().stream()
                .filter(BuildUpstreamCause.class::isInstance)
                .map(BuildUpstreamCause.class::cast)
                .toList());
        }
        assertThat("There should be as many upstream causes as upstream builds", upstreamCauses, hasSize(numberOfUpstreamBuilds));

        Set<WorkflowRun> ups = new HashSet<>();
        for (BuildUpstreamCause up : upstreamCauses) {
            WorkflowRun upstreamRun = (WorkflowRun) up.getUpstreamRun();
            ups.add(upstreamRun);
            FlowExecution execution = upstreamRun.getExecution();
            FlowNode buildTriggerNode = findFirstNodeWithDescriptor(execution, BuildTriggerStep.DescriptorImpl.class);
            assertEquals(buildTriggerNode, execution.getNode(up.getNodeId()), "node id should be build trigger node");
        }
        assertEquals(numberOfUpstreamBuilds, ups.size(), "There should be as many upstream causes as referenced upstream builds");
    }

    static FlowNode findFirstNodeWithDescriptor(FlowExecution execution, Class<BuildTriggerStep.DescriptorImpl> cls) {
        for (FlowNode node : new FlowGraphWalker(execution)) {
            if (node instanceof StepAtomNode stepAtomNode) {
                if (cls.isInstance(stepAtomNode.getDescriptor())) {
                    return stepAtomNode;
                }
            }
        }
        return null;
    }

    @Issue("JENKINS-38339")
    @Test
    void upstreamNodeAction() throws Exception {
        FreeStyleProject downstream = r.createFreeStyleProject("downstream");
        WorkflowJob upstream = r.jenkins.createProject(WorkflowJob.class, "upstream");

        upstream.setDefinition(new CpsFlowDefinition("build 'downstream'", true));
        r.assertBuildStatus(Result.SUCCESS, upstream.scheduleBuild2(0));

        WorkflowRun lastUpstreamRun = upstream.getLastBuild();
        FreeStyleBuild lastDownstreamRun = downstream.getLastBuild();

        final FlowExecution execution = lastUpstreamRun.getExecution();
        FlowNode buildTriggerNode = findFirstNodeWithDescriptor(execution, BuildTriggerStep.DescriptorImpl.class);
        assertNotNull(buildTriggerNode);

        List<BuildUpstreamNodeAction> actions = lastDownstreamRun.getActions(BuildUpstreamNodeAction.class);
        assertEquals(1, actions.size(), "action count");

        BuildUpstreamNodeAction action = actions.get(0);
        assertEquals(action.getUpstreamRunId(), lastUpstreamRun.getExternalizableId(), "correct upstreamRunId");
        assertEquals(buildTriggerNode, execution.getNode(action.getUpstreamNodeId()), "valid upstreamNodeId");
    }

    @SuppressWarnings("deprecation")
    @Test
    void buildFolderProject() throws Exception {
        MockFolder dir1 = r.createFolder("dir1");
        FreeStyleProject downstream = dir1.createProject(FreeStyleProject.class, "downstream");
        downstream.getBuildersList().add(new SleepBuilder(1));

        MockFolder dir2 = r.createFolder("dir2");
        WorkflowJob upstream = dir2.createProject(WorkflowJob.class, "upstream");
        upstream.setDefinition(new CpsFlowDefinition("build '../dir1/downstream'", true));

        r.buildAndAssertSuccess(upstream);
        assertEquals(1, downstream.getBuilds().size());
    }

    @Test
    void buildParallelTests() throws Exception {
        FreeStyleProject p1 = r.createFreeStyleProject("test1");
        p1.getBuildersList().add(new SleepBuilder(1));

        FreeStyleProject p2 = r.createFreeStyleProject("test2");
        p2.getBuildersList().add(new SleepBuilder(1));

        WorkflowJob foo = r.jenkins.createProject(WorkflowJob.class, "foo");
        foo.setDefinition(new CpsFlowDefinition(StringUtils.join(Collections.singletonList("""
                parallel(test1: {
                          build('test1');
                        }, test2: {
                          build('test2');
                        })"""), "\n"), true));

        r.buildAndAssertSuccess(foo);
    }


    @Test
    void abortBuild() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("test1");
        p.getBuildersList().add(new SleepBuilder(Long.MAX_VALUE));

        WorkflowJob foo = r.jenkins.createProject(WorkflowJob.class, "foo");
        foo.setDefinition(new CpsFlowDefinition(StringUtils.join(Collections.singletonList("build('test1');"), "\n"), true));

        QueueTaskFuture<WorkflowRun> q = foo.scheduleBuild2(0);
        WorkflowRun b = q.getStartCondition().get();

        CpsFlowExecution e = (CpsFlowExecution) b.getExecutionPromise().get();
        e.waitForSuspension();

        FreeStyleBuild fb=null;
        while (fb==null) {
            fb = p.getBuildByNumber(1);
            Thread.sleep(10);
        }
        fb.getExecutor().interrupt();

        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(fb));
        r.assertBuildStatus(Result.ABORTED, q.get());
    }

    @Issue("JENKINS-49073")
    @Test
    void downstreamResult() throws Exception {
        downstreamResult(Result.SUCCESS);
        downstreamResult(Result.UNSTABLE);
        downstreamResult(Result.FAILURE);
        downstreamResult(Result.NOT_BUILT);
        downstreamResult(Result.ABORTED);
    }

    private void downstreamResult(Result result) throws Exception {
        FreeStyleProject ds = r.createFreeStyleProject();
        ds.getBuildersList().add(new TestBuilder() {
            @Override public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
                build.setResult(result);
                return true;
            }
        });
        WorkflowJob us = r.createProject(WorkflowJob.class);
        us.setDefinition(new CpsFlowDefinition(String.format("build '%s'", ds.getName()), true));
        r.assertBuildStatus(result, us.scheduleBuild2(0));
    }

    @Test
    void cancelBuildQueue() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("test1");
        p.getBuildersList().add(new SleepBuilder(Long.MAX_VALUE));

        WorkflowJob foo = r.jenkins.createProject(WorkflowJob.class, "foo");
        foo.setDefinition(new CpsFlowDefinition(StringUtils.join(Collections.singletonList("build('test1');"), "\n"), true));

        r.jenkins.setNumExecutors(0); //should force freestyle build to remain in the queue?

        QueueTaskFuture<WorkflowRun> q = foo.scheduleBuild2(0);

        WorkflowRun b = foo.scheduleBuild2(0).waitForStart();
        r.waitForMessage("Scheduling project", b);
        CpsFlowExecution e = (CpsFlowExecution) b.getExecutionPromise().get();
        e.waitForSuspension();

        Queue.Item[] items = r.jenkins.getQueue().getItems();
        assertEquals(1, items.length);
        r.jenkins.getQueue().cancel(items[0]);

        r.assertBuildStatus(Result.FAILURE,q.get());
    }

    /** Interrupting the flow ought to interrupt its downstream builds too, even across nested parallel branches. */
    @Test
    void interruptFlow() throws Exception {
        FreeStyleProject ds1 = r.createFreeStyleProject("ds1");
        ds1.getBuildersList().add(new SleepBuilder(Long.MAX_VALUE));
        FreeStyleProject ds2 = r.createFreeStyleProject("ds2");
        ds2.getBuildersList().add(new SleepBuilder(Long.MAX_VALUE));
        FreeStyleProject ds3 = r.createFreeStyleProject("ds3");
        ds3.getBuildersList().add(new SleepBuilder(Long.MAX_VALUE));
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("parallel ds1: {build 'ds1'}, ds23: {parallel ds2: {build 'ds2'}, ds3: {build 'ds3'}}", true));
        r.jenkins.setNumExecutors(3);
        r.jenkins.setNodes(r.jenkins.getNodes()); // TODO https://github.com/jenkinsci/jenkins/pull/1596 renders this workaround unnecessary
        WorkflowRun usb = us.scheduleBuild2(0).getStartCondition().get();
        assertEquals(1, usb.getNumber());

        await().until(() -> (ds1.getLastBuild() != null && ds2.getLastBuild() != null && ds3.getLastBuild() != null));

        assertEquals(1, ds1.getLastBuild().getNumber());
        assertEquals(1, ds2.getLastBuild().getNumber());
        assertEquals(1, ds3.getLastBuild().getNumber());
        // Same as X button in UI.
        // Should be the same as, e.g., GerritTrigger.RunningJobs.cancelJob, which calls Executor.interrupt directly.
        // (Not if the Executor.currentExecutable is an AfterRestartTask.Body, though in that case probably the FreeStyleBuild would have been killed by restart anyway!)
        usb.doStop();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(usb));
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(ds1.getLastBuild()));
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(ds2.getLastBuild()));
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(ds3.getLastBuild()));
    }

    @Issue("JENKINS-31902")
    @Test
    void interruptFlowDownstreamFlow() throws Exception {
        WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("semaphore 'ds'", true));
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        WorkflowRun usb = us.scheduleBuild2(0).getStartCondition().get();
        assertEquals(1, usb.getNumber());
        SemaphoreStep.waitForStart("ds/1", null);
        WorkflowRun dsb = ds.getLastBuild();
        assertEquals(1, dsb.getNumber());
        usb.doStop();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(usb));
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(dsb));
    }

    @Test
    void interruptFlowNonPropagate() throws Exception {
        WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("semaphore 'ds'", true));
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("while (true) {build job: 'ds', propagate: false}", true));
        WorkflowRun usb = us.scheduleBuild2(0).getStartCondition().get();
        assertEquals(1, usb.getNumber());
        SemaphoreStep.waitForStart("ds/1", null);
        WorkflowRun dsb = ds.getLastBuild();
        assertEquals(1, dsb.getNumber());
        usb.doStop();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(usb));
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(dsb));
    }

    @SuppressWarnings("deprecation")
    @Test
    void triggerWorkflow() throws Exception {
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("echo 'OK'", true));
        r.buildAndAssertSuccess(us);
        assertEquals(1, ds.getBuilds().size());
    }

    @Issue("JENKINS-31897")
    @Test
    void parameters() throws Exception {
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        FreeStyleProject ds = r.jenkins.createProject(FreeStyleProject.class, "ds");
        ds.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("branch", "master"), new BooleanParameterDefinition("extra", false, null)));
        CaptureEnvironmentBuilder env = new CaptureEnvironmentBuilder();
        ds.getBuildersList().add(env);
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        WorkflowRun us1 = r.buildAndAssertSuccess(us);
        assertEquals("1", env.getEnvVars().get("BUILD_NUMBER"));
        assertEquals("master", env.getEnvVars().get("branch"));
        assertEquals("false", env.getEnvVars().get("extra"));
        Cause.UpstreamCause cause = ds.getBuildByNumber(1).getCause(Cause.UpstreamCause.class);
        assertNotNull(cause);
        assertEquals(us1, cause.getUpstreamRun());
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', parameters: [string(name: 'branch', value: 'release')]", true));
        r.buildAndAssertSuccess(us);
        assertEquals("2", env.getEnvVars().get("BUILD_NUMBER"));
        assertEquals("release", env.getEnvVars().get("branch"));
        assertEquals("false", env.getEnvVars().get("extra")); //
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', parameters: [string(name: 'branch', value: 'release'), booleanParam(name: 'extra', value: true)]", true));
        r.buildAndAssertSuccess(us);
        assertEquals("3", env.getEnvVars().get("BUILD_NUMBER"));
        assertEquals("release", env.getEnvVars().get("branch"));
        assertEquals("true", env.getEnvVars().get("extra"));
    }

    @Issue("JENKINS-26123")
    @Test
    void noWait() throws Exception {
        r.createFreeStyleProject("ds").setAssignedLabel(Label.get("nonexistent"));
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', wait: false", true));
        r.buildAndAssertSuccess(us);
    }

    @Test
    void waitForStart() throws Exception {
        FreeStyleProject ds = r.createFreeStyleProject("ds");
        ds.getBuildersList().add(new FailureBuilder());
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', waitForStart: true", true));
        r.assertLogContains("Starting building:", r.buildAndAssertSuccess(us));
        r.assertBuildStatus(Result.FAILURE, r.waitForCompletion(ds.getLastBuild()));
    }

    @Test
    void rejectedStart() throws Exception {
        r.createFreeStyleProject("ds");
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        // wait: true also fails as expected w/o fix, just more slowly (test timeout):
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', wait: false", true));
        r.assertLogContains("Failed to trigger build of ds", r.assertBuildStatus(Result.FAILURE, us.scheduleBuild2(0)));
    }

    @TestExtension("rejectedStart")
    public static final class QDH extends Queue.QueueDecisionHandler {
        @Override
        public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
            return p instanceof WorkflowJob; // i.e., refuse FreestyleProject
        }
    }

    @Issue("JENKINS-25851")
    @Test
    void buildVariables() throws Exception {
        r.createFreeStyleProject("ds").addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("param", "default")));
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("echo \"build var: ${build(job: 'ds', parameters: [string(name: 'param', value: 'override')]).buildVariables.param}\"", true));
        r.assertLogContains("build var: override", r.buildAndAssertSuccess(us));
    }

    @Issue("JENKINS-29169")
    @Test
    void buildVariablesWorkflow() throws Exception {
        WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("env.RESULT = \"ds-${env.BUILD_NUMBER}\"", true));
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("def vars = build('ds').buildVariables; echo \"received RESULT=${vars.RESULT} vs. BUILD_NUMBER=${vars.BUILD_NUMBER}\"", true));
        r.assertLogContains("received RESULT=ds-1 vs. BUILD_NUMBER=null", r.buildAndAssertSuccess(us));
        ds.getBuildByNumber(1).delete();
    }

    @Issue("JENKINS-28063")
    @Test
    void coalescedQueue() throws Exception {
        FreeStyleProject ds = r.createFreeStyleProject("ds");
        ds.setQuietPeriod(3);
        ds.setConcurrentBuild(true);
        ds.getBuildersList().add(new SleepBuilder(3000));
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("echo \"triggered #${build('ds').number}\"", true));
        WorkflowRun us1 = us.scheduleBuild2(0).waitForStart();
        assertEquals(1, us1.getNumber());
        r.waitForMessage("Scheduling project: ds", us1);
        QueueTaskFuture<WorkflowRun> us2F = us.scheduleBuild2(0);
        r.assertLogContains("triggered #1", r.waitForCompletion(us1));
        WorkflowRun us2 = us2F.get();
        assertEquals(2, us2.getNumber());
        r.assertLogContains("triggered #1", us2);
        FreeStyleBuild ds1 = ds.getLastBuild();
        assertEquals(1, ds1.getNumber());
        assertEquals(2, ds1.getCauses().size()); // 2× UpstreamCause
    }

    @Issue("http://stackoverflow.com/q/32228590/12916")
    @Test
    void nonCoalescedQueueParallel() throws Exception {
        r.jenkins.setNumExecutors(5);
        FreeStyleProject ds = r.createFreeStyleProject("ds");
        ds.setConcurrentBuild(true);
        ds.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("which", null)));
        ds.getBuildersList().add(new SleepBuilder(3000));
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def branches = [:]
                        for (int i = 0; i < 5; i++) {
                          def which = "${i}"
                          branches["branch${i}"] = {
                            build job: 'ds', parameters: [string(name: 'which', value: which)]
                          }
                        }
                        parallel branches""", true));
        r.buildAndAssertSuccess(us);
        FreeStyleBuild ds1 = ds.getLastBuild();
        assertEquals(5, ds1.getNumber());
    }

    @Issue("JENKINS-39454")
    @Test
    void raceCondition() throws Exception {
        logging.record(BuildTriggerStepExecution.class.getPackage().getName(), Level.FINE).record(Queue.class, Level.FINE).record(Executor.class, Level.FINE);
        r.jenkins.setQuietPeriod(0);
        WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("sleep 1", true));
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("def rebuild() {for (int i = 0; i < 20; i++) {build 'ds'}}; parallel A: {rebuild()}, B: {rebuild()}, C: {rebuild()}", true));
        r.buildAndAssertSuccess(us);
    }

    @Issue("JENKINS-31897")
    @Test
    void defaultParameters() throws Exception {
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', parameters: [string(name: 'PARAM1', value: 'first')]", true));
        WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
        ds.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("PARAM1", "p1"), new StringParameterDefinition("PARAM2", "p2")));
        // TODO use params when updating workflow-cps/workflow-job
        ds.setDefinition(new CpsFlowDefinition("echo \"${PARAM1} - ${PARAM2}\"", true));
        r.buildAndAssertSuccess(us);
        r.assertLogContains("first - p2", ds.getLastBuild());
    }

    @LocalData
    @Test
    void storedForm() throws Exception {
        WorkflowJob us = r.jenkins.getItemByFullName("us", WorkflowJob.class);
        WorkflowRun us1 = us.getBuildByNumber(1);
        WorkflowJob ds = r.jenkins.getItemByFullName("ds", WorkflowJob.class);
        WorkflowRun ds1 = ds.getBuildByNumber(1);
        ds1.setDescription("something");
        r.assertBuildStatusSuccess(r.waitForCompletion(ds1));
        r.assertBuildStatusSuccess(r.waitForCompletion(us1));
    }

    @Test
    @Issue("JENKINS-38887")
    void triggerOrgFolder() throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("foo");
            WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
            us.setDefinition(new CpsFlowDefinition("build job:'ds', wait:false", true));
            OrganizationFolder ds = r.jenkins.createProject(OrganizationFolder.class, "ds");
            ds.getSCMNavigators().add(new MockSCMNavigator(c, new MockSCMDiscoverBranches()));
            ds.getProjectFactories().add(new DummyMultiBranchProjectFactory());
            r.waitUntilNoActivity();
            assertThat(ds.getComputation().getResult(), nullValue());
            r.buildAndAssertSuccess(us);
            r.waitUntilNoActivity();
            assertThat(ds.getComputation().getResult(), notNullValue());
        }
    }

    public static class DummyMultiBranchProjectFactory extends MultiBranchProjectFactory {
        @Override
        public boolean recognizes(@NonNull ItemGroup<?> parent, @NonNull String name,
                                  @NonNull List<? extends SCMSource> scmSources,
                                  @NonNull Map<String, Object> attributes,
                                  @CheckForNull SCMHeadEvent<?> event, @NonNull TaskListener listener) {
            return false;
        }

        @TestExtension("triggerOrgFolder")
        public static class DescriptorImpl extends MultiBranchProjectFactoryDescriptor {

            @Override
            public MultiBranchProjectFactory newInstance() {
                return new DummyMultiBranchProjectFactory();
            }
        }
    }

    @Issue("SECURITY-433")
    @Test
    void permissions() throws Exception {
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("", true));
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        QueueItemAuthenticatorConfiguration.get().getAuthenticators().add(new MockQueueItemAuthenticator(Collections.singletonMap("us", User.getById("dev", true).impersonate())));
        // Control case: dev can do anything to ds.
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("dev"));
        r.buildAndAssertSuccess(us);
        // Main test case: dev can see ds but not build it.
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.READ, Computer.BUILD).everywhere().to("dev").grant(Item.READ).onItems(ds).to("dev"));
        r.assertLogContains("dev is missing the Job/Build permission", r.assertBuildStatus(Result.FAILURE, us.scheduleBuild2(0)));
        // Aux test case: dev cannot see ds.
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.READ, Computer.BUILD).everywhere().to("dev"));
        r.assertLogContains("No item named ds found", r.assertBuildStatus(Result.FAILURE, us.scheduleBuild2(0)));
        // Aux test case: dev can learn of the existence of ds but no more.
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.READ, Computer.BUILD).everywhere().to("dev").grant(Item.DISCOVER).onItems(ds).to("dev"));
        r.assertLogContains("Please login to access job ds", r.assertBuildStatus(Result.FAILURE, us.scheduleBuild2(0)));
    }

    @Issue("JENKINS-48632")
    @Test
    void parameterDescriptions() throws Exception {
        WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("""
                properties([
                  parameters([
                    booleanParam(defaultValue: true, description: 'flag description', name: 'flag'),
                    string(defaultValue: 'default string', description: 'strParam description', name: 'strParam')
                  ])
                ])
                """, true));
        // Define the parameters
        r.buildAndAssertSuccess(ds);

        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', parameters: [booleanParam(name: 'flag', value: false)]\n", true));
        r.buildAndAssertSuccess(us);

        r.waitUntilNoActivity();

        WorkflowRun r = ds.getBuildByNumber(2);
        assertNotNull(r);

        ParametersAction action = r.getAction(ParametersAction.class);
        assertNotNull(action);

        ParameterValue flagValue = action.getParameter("flag");
        assertNotNull(flagValue);
        assertEquals("flag description", flagValue.getDescription());

        ParameterValue strValue = action.getParameter("strParam");
        assertNotNull(strValue);
        assertEquals("strParam description", strValue.getDescription());
    }

    @Issue("JENKINS-52038")
    @Test
    void invalidChoiceParameterValue() throws Exception {
        WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("""
                properties([
                  parameters([
                    choice(name:'letter', description: 'a letter', choices: ['a', 'b'].join("\\n"))
                  ])
                ])
                """, true));
        // Define the parameters
        r.buildAndAssertSuccess(ds);

        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', parameters: [string(name: 'letter', value: 'c')]\n", true));
        r.assertLogContains("Invalid parameter value: (StringParameterValue) letter='c'",
                r.assertBuildStatus(Result.FAILURE, us.scheduleBuild2(0)));
    }

    @Test
    void snippetizerRoundTrip() throws Exception {
        SnippetizerTester st = new SnippetizerTester(r);
        BuildTriggerStep step = new BuildTriggerStep("downstream");
        st.assertRoundTrip(step, "build 'downstream'");
        step.setParameters(Arrays.asList(new StringParameterValue("branch", "default"), new BooleanParameterValue("correct", true)));
        // Note: This does not actually test the format of the JSON produced by the snippet generator for parameters, see generateSnippet* for tests of that behavior.
        st.assertRoundTrip(step, "build job: 'downstream', parameters: [string(name: 'branch', value: 'default'), booleanParam(name: 'correct', value: true)]");
        // Passwords parameters are handled specially via CustomDescribableModel
        step.setParameters(Collections.singletonList(new PasswordParameterValue("param-name", "secret")));
        st.assertRoundTrip(step, "build job: 'downstream', parameters: [password(name: 'param-name', value: 'secret')]");
    }

    @Issue("JENKINS-26093")
    @Test
    void generateSnippetForBuildTrigger() throws Exception {
        SnippetizerTester st = new SnippetizerTester(r);
        MockFolder d1 = r.createFolder("d1");
        FreeStyleProject ds = d1.createProject(FreeStyleProject.class, "ds");
        MockFolder d2 = r.createFolder("d2");
        WorkflowJob us = d2.createProject(WorkflowJob.class, "us");
        ds.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("key", ""), new BooleanParameterDefinition("flag", false, "")));
        String snippet = "build job: '../d1/ds', parameters: [string(name: 'key', value: 'stuff'), booleanParam(name: 'flag', value: true)]";
        st.assertGenerateSnippet("{'stapler-class':'" + BuildTriggerStep.class.getName() + "', 'job':'../d1/ds', 'parameter': [{'name':'key', 'value':'stuff'}, {'name':'flag', 'value':true}]}", snippet, us.getAbsoluteUrl() + "configure");
    }

    @Issue("JENKINS-29739")
    @Test
    void generateSnippetForBuildTriggerSingle() throws Exception {
        SnippetizerTester st = new SnippetizerTester(r);
        FreeStyleProject ds = r.jenkins.createProject(FreeStyleProject.class, "ds1");
        FreeStyleProject us = r.jenkins.createProject(FreeStyleProject.class, "us1");
        ds.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("key", "")));
        String snippet = "build job: 'ds1', parameters: [string(name: 'key', value: 'stuff')]";
        st.assertGenerateSnippet("{'stapler-class':'" + BuildTriggerStep.class.getName() + "', 'job':'ds1', 'parameter': {'name':'key', 'value':'stuff'}}", snippet, us.getAbsoluteUrl() + "configure");
    }

    @Test
    void generateSnippetForBuildTriggerNone() throws Exception {
        SnippetizerTester st = new SnippetizerTester(r);
        FreeStyleProject ds = r.jenkins.createProject(FreeStyleProject.class, "ds0");
        FreeStyleProject us = r.jenkins.createProject(FreeStyleProject.class, "us0");
        st.assertGenerateSnippet("{'stapler-class':'" + BuildTriggerStep.class.getName() + "', 'job':'ds0'}", "build 'ds0'", us.getAbsoluteUrl() + "configure");
    }

    @Test
    void buildStepDocs() throws Exception {
        try {
            SnippetizerTester.assertDocGeneration(BuildTriggerStep.class);
        } catch (Exception e) {
            // TODO: Jenkins 2.236+ broke structs-based databinding and introspection of PasswordParameterValue, JENKINS-62305.
            assumeFalse(e.getMessage().contains("There's no @DataBoundConstructor on any constructor of class hudson.util.Secret"));
            throw e;
        }
    }

    @Test
    void automaticParameterConversion() throws Exception {
        // Downstream Job
        WorkflowJob ds = r.createProject(WorkflowJob.class);
        ds.addProperty(new ParametersDefinitionProperty(
                new PasswordParameterDefinition("my-password", "default", "description"),
                new BooleanParameterDefinition("my-boolean", false, "description")
        ));
        ds.setDefinition(new CpsFlowDefinition(
                """
                        echo('Password: ' + params['my-password'])
                        echo('Boolean: ' + params['my-boolean'])
                        """, true));
        // Upstream Job
        WorkflowJob us = r.createProject(WorkflowJob.class);
        String def = "build(job: '" + ds.getName() + "', parameters: [%s])";

        // A password parameter passed as a string parameter is converted.
        us.setDefinition(new CpsFlowDefinition(String.format(def, "string(name: 'my-password', value: 'secret')"), true));
        WorkflowRun us1 = r.buildAndAssertSuccess(us);
        WorkflowRun ds1 = ds.getBuildByNumber(1);
        r.assertLogContains("The parameter 'my-password' did not have the type expected", us1);
        r.assertLogContains("Password: secret", ds1);
        assertThat(getParameter(ds1, "my-password").getDescription(),
                equalTo(Messages.BuildTriggerStepExecution_convertedParameterDescription("description", "Password Parameter", us1.toString())));

        // A password parameter passed as a text parameter is converted.
        us.setDefinition(new CpsFlowDefinition(String.format(def, "text(name: 'my-password', value: 'secret')"), true));
        WorkflowRun us2 = r.buildAndAssertSuccess(us);
        WorkflowRun ds2 = ds.getBuildByNumber(2);
        r.assertLogContains("The parameter 'my-password' did not have the type expected", us2);
        r.assertLogContains("Password: secret", ds2);
        assertThat(getParameter(ds2, "my-password").getDescription(),
                equalTo(Messages.BuildTriggerStepExecution_convertedParameterDescription("description", "Password Parameter", us2.toString())));

        // A password parameter passed as a password parameter is not converted.
        us.setDefinition(new CpsFlowDefinition(String.format(def, "password(name: 'my-password', value: 'secret')"), true));
        WorkflowRun us3 = r.buildAndAssertSuccess(us);
        WorkflowRun ds3 = ds.getBuildByNumber(3);
        r.assertLogNotContains("The parameter 'my-password' did not have the type expected", us3);
        r.assertLogContains("Password: secret", ds3);

        // A password parameter passed as a boolean parameter is not converted.
        // This is an example of the case mentioned in the TODO comment in `BuildTriggerStepExecution.completeDefaultParameters`.
        us.setDefinition(new CpsFlowDefinition(String.format(def, "booleanParam(name: 'my-password', value: true)"), true));
        WorkflowRun us4 = r.buildAndAssertSuccess(us);
        WorkflowRun ds4 = ds.getBuildByNumber(4);
        r.assertLogNotContains("The parameter 'my-password' did not have the type expected", us4);
        r.assertLogContains("Password: true", ds4);

        // A boolean parameter passed as a string parameter
        us.setDefinition(new CpsFlowDefinition(String.format(def, "string(name: 'my-boolean', value: 'true')"), true));
        WorkflowRun us5 = r.buildAndAssertSuccess(us);
        WorkflowRun ds5 = ds.getBuildByNumber(5);
        r.assertLogContains("The parameter 'my-boolean' did not have the type expected", us5);
        r.assertLogContains("Boolean: true", ds5);
        assertThat(getParameter(ds5, "my-boolean").getDescription(),
                equalTo(Messages.BuildTriggerStepExecution_convertedParameterDescription("description", "Boolean Parameter", us5.toString())));

        // A boolean parameter passed as a password parameter.
        // This is an example of the case mentioned in the TODO comment in `BuildTriggerStepExecution.completeDefaultParameters`.
        us.setDefinition(new CpsFlowDefinition(String.format(def, "password(name: 'my-boolean', value: 'secret')"), true));
        WorkflowRun us6 = r.buildAndAssertSuccess(us);
        WorkflowRun ds6 = ds.getBuildByNumber(6);
        r.assertLogNotContains("The parameter 'my-boolean' did not have the type expected", us6);
        r.assertLogContains("Boolean: secret", ds6);

        // A boolean parameter passed as a boolean.
        us.setDefinition(new CpsFlowDefinition(String.format(def, "booleanParam(name: 'my-boolean', value: true)"), true));
        WorkflowRun us7 = r.buildAndAssertSuccess(us);
        WorkflowRun ds7 = ds.getBuildByNumber(7);
        r.assertLogNotContains("The parameter 'my-boolean' did not have the type expected", us7);
        r.assertLogContains("Boolean: true", ds7);
    }

    @Issue("JENKINS-62483")
    @Test
    void maintainParameterListOrder() throws Exception {
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        String params = "[string(name: 'PARAM1', value: 'p1'), " +
                "string(name: 'PARAM2', value: 'p2'), " +
                "string(name: 'PARAM3', value: 'p3'), " +
                "string(name: 'PARAM4', value: 'p4')]";
        us.setDefinition(new CpsFlowDefinition(String.format("build job: 'ds', parameters: %s", params), true));
        WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
        ds.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("PARAM1", ""),
                new StringParameterDefinition("PARAM2", ""),
                new StringParameterDefinition("PARAM3", ""),
                new StringParameterDefinition("PARAM4", "")));
        ds.setDefinition(new CpsFlowDefinition("echo \"${PARAM1} - ${PARAM2} - ${PARAM3} - ${PARAM4}\"", true));
        r.buildAndAssertSuccess(us);
        ParametersAction buildParams = ds.getLastBuild().getAction(ParametersAction.class);
        List<String> parameterNames = new ArrayList<>();
        for (ParameterValue parameterValue : buildParams.getAllParameters()) {
            parameterNames.add(parameterValue.getName());
        }
        assertThat(parameterNames, equalTo(Arrays.asList("PARAM1", "PARAM2", "PARAM3", "PARAM4")));
    }

    @Issue("JENKINS-62305")
    @Test
    void passwordParameter() throws Exception {
        WorkflowJob ds = r.createProject(WorkflowJob.class);
        ds.addProperty(new ParametersDefinitionProperty(
                new PasswordParameterDefinition("my-password", "", "")));
        ds.setDefinition(new CpsFlowDefinition(
                "echo('Password: ' + params['my-password'])\n", true));
        WorkflowJob us = r.createProject(WorkflowJob.class);
        us.setDefinition(new CpsFlowDefinition(
                "build(job: '" + ds.getName() + "', parameters: [password(name: 'my-password', value: 'secret')])", true));
        r.buildAndAssertSuccess(us);
        r.assertLogContains("Password: secret", ds.getBuildByNumber(1));
    }

    @Test
    void credentialsParameter() throws Exception {
        WorkflowJob ds = r.createProject(WorkflowJob.class);
        ds.addProperty(new ParametersDefinitionProperty(
                new CredentialsParameterDefinition("my-credential", "", "", Credentials.class.getName(), false)));
        ds.setDefinition(new CpsFlowDefinition(
                "echo('Credential: ' + params['my-credential'])\n", true));
        WorkflowJob us = r.createProject(WorkflowJob.class);
        us.setDefinition(new CpsFlowDefinition(
                "build(job: '" + ds.getName() + "', parameters: [credentials(name: 'my-credential', value: 'credential-id')])", true));
        r.buildAndAssertSuccess(us);
        r.assertLogContains("Credential: credential-id", ds.getBuildByNumber(1));
    }

    @Issue("SECURITY-2519")
    @Test
    void generateSnippetForBuildTriggerWhenDefaultPasswordParameterThenDoNotReturnRealPassword() throws Exception {
        SnippetizerTester st = new SnippetizerTester(r);
        FreeStyleProject us = r.createProject(FreeStyleProject.class, "project1");
        us.addProperty(new ParametersDefinitionProperty(
                new PasswordParameterDefinition("password", "mySecret", "description")
        ));

        String snippet = "build job: 'project1', parameters: [password(name: 'password', description: 'description', value: '" + PasswordParameterDefinition.DEFAULT_VALUE + "')]";

        st.assertGenerateSnippet("{'stapler-class':'" + BuildTriggerStep.class.getName() + "', 'job':'project1', 'parameter': {'name': 'password', 'description': 'description', 'value': '" + PasswordParameterDefinition.DEFAULT_VALUE + "'}}", snippet, us.getAbsoluteUrl() + "configure");
    }

    @LocalData
    @Test
    void downstreamFailureCauseSerialCompatibility() throws Exception {
        // LocalData created as of eace550 with this test script:
        /*
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDisplayName("DoWnStReAm");
        ds.setDefinition(new CpsFlowDefinition("error 'oops!'", true));
        j.buildAndAssertStatus(Result.FAILURE, us);
        */
        var us = r.jenkins.getItemByFullName("us", WorkflowJob.class);
        var b = us.getBuildByNumber(1);
        var failure = (FlowInterruptedException) b.getExecution().getCauseOfFailure();
        assertThat(failure.getCauses(), contains(instanceOf(DownstreamFailureCause.class)));
        var cause = (DownstreamFailureCause) failure.getCauses().get(0);
        assertThat(cause.getShortDescription(), containsString("DoWnStReAm #1 completed with status FAILURE"));
        var writer = new StringWriter();
        try (var tl = new StreamTaskListener(writer)) {
            cause.print(tl);
        }
        assertThat(writer.toString(), containsString("DoWnStReAm #1 completed with status FAILURE"));
    }

    @Test
    void downstreamFailureCauseMessage() throws Exception {
        WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDisplayName("DoWnStReAm");
        ds.setDefinition(new CpsFlowDefinition("error 'oops!'", true));
        var b = r.buildAndAssertStatus(Result.FAILURE, us);
        var failure = (FlowInterruptedException) b.getExecution().getCauseOfFailure();
        assertThat(failure.getCauses(), contains(instanceOf(DownstreamFailureCause.class)));
        var cause = (DownstreamFailureCause) failure.getCauses().get(0);
        // DownstreamFailureCause now uses Job.getFullName, not Job.getFullDisplayName.
        assertThat(cause.getShortDescription(), containsString("ds #1 completed with status FAILURE"));
        var writer = new StringWriter();
        try (var tl = new StreamTaskListener(writer)) {
            cause.print(tl);
        }
        assertThat(writer.toString(), containsString("ds #1 completed with status FAILURE"));
    }

    private static ParameterValue getParameter(Run<?, ?> run, String parameterName) {
        return run.getAction(ParametersAction.class).getParameter(parameterName);
    }
}
