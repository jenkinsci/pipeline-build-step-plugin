/*
 * The MIT License
 *
 * Copyright 2015 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.support.steps.build;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.htmlunit.AlertHandler;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.html.HtmlTextInput;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@WithJenkins
class BuildTriggerStepConfigTest {

    private JenkinsRule r;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        r = rule;
    }

    @Issue("JENKINS-26692")
    @Test
    void configRoundTrip() throws Exception {
        BuildTriggerStep s = new BuildTriggerStep("ds");
        s = new StepConfigTester(r).configRoundTrip(s);
        assertNull(s.getQuietPeriod());
        assertTrue(s.isPropagate());
        s.setPropagate(false);
        s.setQuietPeriod(5);
        s = new StepConfigTester(r).configRoundTrip(s);
        assertEquals(Integer.valueOf(5), s.getQuietPeriod());
        assertFalse(s.isPropagate());
        s.setQuietPeriod(0);
        s = new StepConfigTester(r).configRoundTrip(s);
        assertEquals(Integer.valueOf(0), s.getQuietPeriod());
    }

    @Issue("JENKINS-38114")
    @Test
    void helpWait() throws Exception {
        assertThat(r.createWebClient().goTo(r.executeOnServer(()
                                -> r.jenkins.getDescriptorByType(BuildTriggerStep.DescriptorImpl.class).getHelpFile("wait"))
                        .replaceFirst("^/", ""), /* TODO why is no content type set? */null)
                .getWebResponse().getContentAsString(), containsString("<dt><code>buildVariables</code></dt>"));
    }

    @Test
    @Issue("SECURITY-3019")
    void escapedSnippetConfig() throws IOException, SAXException {
        final String jobName = "'+alert(123)+'";
        WorkflowJob j = r.createProject(WorkflowJob.class, jobName);
        try (JenkinsRule.WebClient webClient = r.createWebClient()) {
            Alerter alerter = new Alerter();
            webClient.setAlertHandler(alerter);
            HtmlPage page = webClient.getPage(j, "pipeline-syntax");
            final HtmlSelect select = (HtmlSelect)page.getElementsByTagName("select").get(0);
            page = select.setSelectedAttribute("build: Build a job", true);
            webClient.waitForBackgroundJavaScript(2000);
            //final HtmlForm config = page.getFormByName("config");
            final List<DomElement> inputs = page.getElementsByName("_.job"); //config.getInputsByName("_.job");
            assertThat(inputs, hasSize(1));
            final HtmlTextInput jobNameInput = (HtmlTextInput)inputs.get(0);
            jobNameInput.focus();
            jobNameInput.blur();
            assertThat(alerter.messages, empty()); //Fails before the fix
        }

    }

    static class Alerter implements AlertHandler {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void handleAlert(final Page page, final String message) {
            messages.add(message);
        }
    }

}