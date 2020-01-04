package org.jenkinsci.plugins.workflow.support.steps.build;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import java.util.List;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.RestartableJenkinsRule;
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

public class AddTriggerBuildLinkStepRestartTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();

    /**
     * Tests starting the downstream job by user interaction after jenkins restart.
     */
    @Test
    public void restartBetweenJobs() {

        this.story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                final FreeStyleProject downstream = story.j.createFreeStyleProject("downstream");
                downstream.addProperty(
                    new ParametersDefinitionProperty(
                        new StringParameterDefinition("branch", "master"),
                        new BooleanParameterDefinition("extra", false, null))
                );
                final WorkflowJob upstream = story.j.jenkins.createProject(WorkflowJob.class, "upstream");
                upstream.setDefinition(new CpsFlowDefinition(
                    "addTriggerBuildLink job: 'downstream', linkLabel: 'Trigger delivery', linkTooltip: 'Delivers product to production.'", true));
                AddTriggerBuildLinkStepRestartTest.this.story.j.assertBuildStatus(Result.SUCCESS, upstream.scheduleBuild2(0));

                final FreeStyleBuild lastDownstreamRun = downstream.getLastBuild();
                // Downstream build has not yet been started
                assertThat(lastDownstreamRun, is(nullValue()));
                final WorkflowRun lastUpstreamRun = upstream.getLastBuild();
                final List<TriggerBuildLinkAction> triggerActions = lastUpstreamRun.getActions(TriggerBuildLinkAction.class);
                assertThat(triggerActions, hasSize(1));
            }
        });

        this.story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowJob upstream = story.j.jenkins.getItemByFullName("upstream", WorkflowJob.class);
                FreeStyleProject downstream = story.j.jenkins.getItemByFullName("downstream", FreeStyleProject.class);
                final WorkflowRun lastUpstreamRun = upstream.getLastBuild();
                final List<TriggerBuildLinkAction> triggerActions = lastUpstreamRun.getActions(TriggerBuildLinkAction.class);
                assertThat(triggerActions, hasSize(1));
                TriggerBuildLinkAction triggerAction = triggerActions.get(0);
                
                // Start the downstream build
                HttpResponse response = triggerAction.doStartBuild();
                assertThat(response, is(HttpResponses.forwardToPreviousPage()));
                
                // now the downstream build has been started
                FreeStyleBuild lastDownstreamRun = AddTriggerBuildLinkStepTest.getFirstBuild(downstream);
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
        });
    }
}
