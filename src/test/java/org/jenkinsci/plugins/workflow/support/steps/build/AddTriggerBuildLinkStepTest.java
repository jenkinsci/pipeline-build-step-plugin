package org.jenkinsci.plugins.workflow.support.steps.build;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;

import hudson.model.BooleanParameterDefinition;
import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.User;

@SuppressWarnings("nls")
public final class AddTriggerBuildLinkStepTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule
    public JenkinsRule j = new JenkinsRule();
    @Rule
    public LoggerRule logging = new LoggerRule();

    @Before
    public void runQuickly() throws IOException {
        this.j.jenkins.setQuietPeriod(0);
    }

    @Test 
    public void testAddBuildAction() throws Exception {
        FreeStyleProject downstream = j.createFreeStyleProject("downstream");
        downstream.addProperty(
            new ParametersDefinitionProperty(
                new StringParameterDefinition("branch", "master"), 
                new BooleanParameterDefinition("extra", false, null))
        );
        WorkflowJob upstream = j.jenkins.createProject(WorkflowJob.class, "upstream");

        upstream.setDefinition(new CpsFlowDefinition(
            "addTriggerBuildLink job: 'downstream', linkLabel: 'Trigger delivery', linkTooltip: 'Delivers product to production.', parameters: [string(name: 'branch', value: 'feature/important')]", true));
        j.assertBuildStatus(Result.SUCCESS, upstream.scheduleBuild2(0));

        WorkflowRun lastUpstreamRun = upstream.getLastBuild();
        FreeStyleBuild lastDownstreamRun = downstream.getLastBuild();
        // Downstream build has not yet been started
        assertThat(lastDownstreamRun, is(nullValue()));

        List<TriggerBuildLinkAction> triggerActions = lastUpstreamRun.getActions(TriggerBuildLinkAction.class);
        assertThat(triggerActions, hasSize(1));
        TriggerBuildLinkAction triggerAction = triggerActions.get(0);
        
        // Start the downstream build
        HttpResponse response = triggerAction.doStartBuild();
        assertThat(response, is(HttpResponses.forwardToPreviousPage()));
        
        // now the downstream build has been started
        lastDownstreamRun = getFirstBuild(downstream);
        assertThat(lastDownstreamRun, is(not(nullValue())));
        
        final FlowExecution execution = lastUpstreamRun.getExecution();

        List<BuildUpstreamNodeAction> actions = lastDownstreamRun.getActions(BuildUpstreamNodeAction.class);
        assertEquals("action count", 1, actions.size());

        BuildUpstreamNodeAction action = actions.get(0);
        assertEquals("correct upstreamRunId", action.getUpstreamRunId(), lastUpstreamRun.getExternalizableId());
        assertNotNull("valid upstreamNodeId", execution.getNode(action.getUpstreamNodeId()));
        
        // Check if the upstream cause and userid cause are present
        Cause.UpstreamCause upstreamCause = lastDownstreamRun.getCause(Cause.UpstreamCause.class);
        assertNotNull(upstreamCause);
        assertEquals(lastUpstreamRun, upstreamCause.getUpstreamRun());
        
        Cause.UserIdCause userIdCause = lastDownstreamRun.getCause(Cause.UserIdCause.class);
        assertNotNull(userIdCause);
        assertThat(userIdCause.getUserId(), is(User.current().getId()));
    }

    static FreeStyleBuild getFirstBuild(FreeStyleProject downstream) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            FreeStyleBuild fb = downstream.getBuildByNumber(1);
            if (fb != null) {
                return fb;
            }
            Thread.sleep(100);
        }
        fail("Build not started");
        return null;
    }
}
