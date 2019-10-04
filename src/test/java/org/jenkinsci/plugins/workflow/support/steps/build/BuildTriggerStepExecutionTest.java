package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.ChoiceParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

public class BuildTriggerStepExecutionTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void runQuickly() throws IOException {
        j.jenkins.setQuietPeriod(0);
    }

    @Test
    public void buildAnotherJob_configuredWithoutParameters() throws Exception {
        WorkflowJob parent = j.jenkins.createProject(WorkflowJob.class, "parent");
        FreeStyleProject child = j.createFreeStyleProject("child");

        parent.setDefinition(new CpsFlowDefinition("build 'child'\n", true));

        assertNull(child.getLastSuccessfulBuild());

        j.buildAndAssertSuccess(parent);
        assertNotNull(child.getLastSuccessfulBuild());
    }

    @Test
    public void buildAnotherJob_configuredWithDefaultParameters() throws Exception {
        WorkflowJob parent = j.jenkins.createProject(WorkflowJob.class, "parent");
        FreeStyleProject child = j.createFreeStyleProject("child");
        child.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("login", "default-login", null),
                new PasswordParameterDefinition("pwd", "default-password", null),
                new ChoiceParameterDefinition("type", new String[]{"choice1", "choice2"}, null)
        ));

        parent.setDefinition(new CpsFlowDefinition("build 'child'\n", true));

        assertNull(child.getLastSuccessfulBuild());

        j.buildAndAssertSuccess(parent);
        assertNotNull(child.getLastSuccessfulBuild());

        ParametersAction parametersAction = child.getLastSuccessfulBuild().getAction(ParametersAction.class);
        List<ParameterValue> allParameters = parametersAction.getAllParameters();
        assertThat(allParameters, contains(
                new ParameterValueLikeMatcher(new StringParameterValue("login", "default-login")),
                new ParameterValueLikeMatcher(new PasswordParameterValue("pwd", "default-password")),
                new ParameterValueLikeMatcher(new StringParameterValue("type", "choice1"))
        ));
    }

    @Test
    public void buildAnotherJob_configuredWithDefaultParameters_withProposedParameters() throws Exception {
        WorkflowJob parent = j.jenkins.createProject(WorkflowJob.class, "parent");
        FreeStyleProject child = j.createFreeStyleProject("child");
        child.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("login", "default-login", null),
                new PasswordParameterDefinition("pwd", "default-password", null),
                new ChoiceParameterDefinition("type", new String[]{"choice1", "choice2"}, null)
        ));

        parent.setDefinition(new CpsFlowDefinition("build job: 'child', parameters: [" +
                "string(name: 'login', value: 'my-login'), " +
                "password(name: 'pwd', value: 'my-password'), " +
                "string(name: 'type', value: 'choice2')" +
                "]", true));

        assertNull(child.getLastSuccessfulBuild());

        j.buildAndAssertSuccess(parent);
        assertNotNull(child.getLastSuccessfulBuild());

        ParametersAction parametersAction = child.getLastSuccessfulBuild().getAction(ParametersAction.class);
        List<ParameterValue> allParameters = parametersAction.getAllParameters();
        assertThat(allParameters, contains(
                new ParameterValueLikeMatcher(new StringParameterValue("login", "my-login")),
                new ParameterValueLikeMatcher(new PasswordParameterValue("pwd", "my-password")),
                new ParameterValueLikeMatcher(new StringParameterValue("type", "choice2"))
        ));
    }

    @Test
    public void buildAnotherJob_configuredWithDefaultParameters_withProposedParameters_butMismatchTypes() throws Exception {
        WorkflowJob parent = j.jenkins.createProject(WorkflowJob.class, "parent");
        FreeStyleProject child = j.createFreeStyleProject("child");
        child.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("login", "default-login", null),
                new PasswordParameterDefinition("pwd", "default-password", null),
                new ChoiceParameterDefinition("type", new String[]{"choice1", "choice2"}, null)
        ));

        parent.setDefinition(new CpsFlowDefinition("build job: 'child', parameters: [" +
                "string(name: 'login', value: 'my-login'), " +
                // string instead of password => auto conversion
                "string(name: 'pwd', value: 'my-password'), " +
                "string(name: 'type', value: 'choice2')" +
                "]", true));

        assertNull(child.getLastSuccessfulBuild());

        j.buildAndAssertSuccess(parent);
        assertNotNull(child.getLastSuccessfulBuild());

        ParametersAction parametersAction = child.getLastSuccessfulBuild().getAction(ParametersAction.class);
        List<ParameterValue> allParameters = parametersAction.getAllParameters();
        assertThat(allParameters, contains(
                new ParameterValueLikeMatcher(new StringParameterValue("login", "my-login")),
                // we need to ensure the password is converted to password type to avoid leaking plain text value
                new ParameterValueLikeMatcher(new PasswordParameterValue("pwd", "my-password")),
                new ParameterValueLikeMatcher(new StringParameterValue("type", "choice2"))
        ));

        PasswordParameterValue passwordParameterValue = (PasswordParameterValue) allParameters.stream()
                .filter(parameterValue -> parameterValue instanceof PasswordParameterValue)
                .findFirst()
                .orElseThrow(AssertionError::new);

        assertThat(passwordParameterValue.getDescription(), startsWith(Messages.BuildTriggerStepExecution_passwordConverted()));
    }

    @Test
    public void buildAnotherJob_configuredWithDefaultParameters_withProposedParameters_butNonConvertibleTypes() throws Exception {
        WorkflowJob parent = j.jenkins.createProject(WorkflowJob.class, "parent");
        FreeStyleProject child = j.createFreeStyleProject("child");
        child.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("login", "default-login", null),
                new PasswordParameterDefinition("pwd", "default-password", null),
                new ChoiceParameterDefinition("type", new String[]{"choice1", "choice2"}, null)
        ));

        parent.setDefinition(new CpsFlowDefinition("build job: 'child', parameters: [" +
                "string(name: 'login', value: 'my-login'), " +
                // boolean instead of password => error
                "booleanParam(name: 'pwd', value: true), " +
                "string(name: 'type', value: 'choice2')" +
                "]", true));

        assertNull(child.getLastSuccessfulBuild());

        j.assertBuildStatus(Result.FAILURE, parent.scheduleBuild2(0));

        // was not called at all
        assertNull(child.getLastBuild());

        j.assertLogContains("Value for password parameter 'pwd' is of a non-convertible type 'BooleanParameterValue'. Please use password as parameter type.", parent.getLastBuild());
    }

    private static class ParameterValueLikeMatcher extends TypeSafeMatcher<ParameterValue> {
        private ParameterValue desiredValue;

        private ParameterValueLikeMatcher(ParameterValue desiredValue) {
            this.desiredValue = desiredValue;
        }

        @Override
        public boolean matchesSafely(ParameterValue other) {
            return other.getClass().equals(desiredValue.getClass()) && Objects.equals(other.getValue(), desiredValue.getValue());
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("a parameter value with class ")
                    .appendValue(desiredValue.getClass().getSimpleName())
                    .appendText(" and value ")
                    .appendValue(desiredValue.getValue());
        }
    }
}
