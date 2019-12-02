package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.Extension;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Describable;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.ItemVisitor;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.util.FormValidation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.util.StaplerReferer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Pipeline step that adds an link to the build's page that allows the
 * user to start a downstream build with predefined parameters.
 */
public class AddTriggerBuildLinkStep extends Step implements Serializable {

    private final String job;
    private final String linkLabel;
    private final String linkTooltip;
    private List<ParameterValue> parameters;

    @DataBoundConstructor
    public AddTriggerBuildLinkStep(String job, final String linkLabel, final String linkTooltip) {
        this.job = job;
        this.linkTooltip = linkTooltip;
        this.linkLabel = linkLabel;
    }

    public String getJob() {
        return this.job;
    }
    
    public String getLinkTooltip() {
        return this.linkTooltip;
    }
    
    public String getLinkLabel() {
        return this.linkLabel;
    }

    public List<ParameterValue> getParameters() {
        return this.parameters;
    }

    @DataBoundSetter 
    public void setParameters(List<ParameterValue> parameters) {
        this.parameters = parameters;
    }
    
    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new AddTriggerBuildLinkStepExecution(context, this);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        public DescriptorImpl() {
            super();
        }

        // Note: This is necessary because the JSON format of the parameters produced by config.jelly when
        // using the snippet generator does not match what would be neccessary for databinding to work automatically.
        // For non-snippet generator use, this is unnecessary.
        @Override 
        public Step newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            AddTriggerBuildLinkStep step = (AddTriggerBuildLinkStep) super.newInstance(req, formData);
            // Cf. ParametersDefinitionProperty._doBuild:
            Object parameter = formData.get("parameter");
            JSONArray params = parameter != null ? JSONArray.fromObject(parameter) : null;
            if (params != null) {
                Job<?,?> context = StaplerReferer.findItemFromRequest(Job.class);
                Job<?,?> job = Jenkins.get().getItem(step.getJob(), context, Job.class);
                if (job != null) {
                    ParametersDefinitionProperty pdp = job.getProperty(ParametersDefinitionProperty.class);
                    if (pdp != null) {
                        List<ParameterValue> values = new ArrayList<ParameterValue>();
                        for (Object o : params) {
                            JSONObject jo = (JSONObject) o;
                            String name = jo.getString("name");
                            ParameterDefinition d = pdp.getParameterDefinition(name);
                            if (d == null) {
                                throw new IllegalArgumentException("No such parameter definition: " + name);
                            }
                            ParameterValue parameterValue = d.createValue(req, jo);
                            if (parameterValue != null) {
                                values.add(parameterValue);
                            } else {
                                throw new IllegalArgumentException("Cannot retrieve the parameter value: " + name);
                            }
                        }
                        step.setParameters(values);
                    }
                }
            }
            return step;
        }

        @Override
        public String getFunctionName() {
            return "addTriggerBuildLink";
        }

        @Override
        public String getDisplayName() {
            return "Add a link to build page to trigger a downstream build";
        }

        public AutoCompletionCandidates doAutoCompleteJob(@AncestorInPath ItemGroup<?> container, @QueryParameter final String value) {
            // TODO remove code copy&pasted from AutoCompletionCandidates.ofJobNames when it supports testing outside Item bound
            final AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            class Visitor extends ItemVisitor {
                String prefix;

                Visitor(String prefix) {
                    this.prefix = prefix;
                }

                @Override
                public void onItem(Item i) {
                    String n = contextualNameOf(i);
                    if ((n.startsWith(value) || value.startsWith(n))
                            // 'foobar' is a valid candidate if the current value is 'foo'.
                            // Also, we need to visit 'foo' if the current value is 'foo/bar'
                            && (value.length() > n.length() || !n.substring(value.length()).contains("/"))
                            // but 'foobar/zot' isn't if the current value is 'foo'
                            // we'll first show 'foobar' and then wait for the user to type '/' to show the rest
                            && i.hasPermission(Item.READ)
                        // and read permission required
                            ) {
                        if (i instanceof Queue.Task && n.startsWith(value))
                            candidates.add(n);

                        // recurse
                        String oldPrefix = prefix;
                        prefix = n;
                        super.onItem(i);
                        prefix = oldPrefix;
                    }
                }

                private String contextualNameOf(Item i) {
                    if (prefix.endsWith("/") || prefix.length() == 0)
                        return prefix + i.getName();
                    else
                        return prefix + '/' + i.getName();
                }
            }

            if (container == null || container == Jenkins.get()) {
                new Visitor("").onItemGroup(Jenkins.get());
            } else {
                new Visitor("").onItemGroup(container);
                if (value.startsWith("/"))
                    new Visitor("/").onItemGroup(Jenkins.get());

                for (StringBuilder p = new StringBuilder("../"); value.startsWith(p.toString()); p .append("../")) {
                    container = ((Item) container).getParent();
                    new Visitor(p.toString()).onItemGroup(container);
                }
            }
            return candidates;
            // END of copy&paste
        }

        @Restricted(DoNotUse.class) // for use from config.jelly
        public String getContext() {
            Job<?,?> job = StaplerReferer.findItemFromRequest(Job.class);
            return job != null ? job.getFullName() : null;
        }

        public FormValidation doCheckJob(@AncestorInPath ItemGroup<?> context, @QueryParameter String value) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.warning(Messages.BuildTriggerStep_no_job_configured());
            }
            Item item = Jenkins.get().getItem(value, context, Item.class);
            if (item == null) {
                return FormValidation.error(Messages.BuildTriggerStep_cannot_find(value));
            }
            if (item instanceof Queue.Task) {
                return FormValidation.ok();
            }
            if (item instanceof Describable) {
                return FormValidation.error(Messages.BuildTriggerStep_unsupported(((Describable) item).getDescriptor().getDisplayName()));
            }
            return FormValidation.error(Messages.BuildTriggerStep_unsupported(item.getClass().getName()));
        }
        
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            final Set<Class<?>> result = new LinkedHashSet<>();
            result.add(Run.class);
            return result;
        }
    }
}
