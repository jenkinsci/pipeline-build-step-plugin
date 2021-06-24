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
import hudson.model.PasswordParameterValue;
import hudson.model.Queue;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.structs.describable.CustomDescribableModel;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.util.StaplerReferer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class BuildTriggerStep extends AbstractStepImpl {

    private final String job;
    private List<ParameterValue> parameters;
    private boolean wait = true;
    private boolean propagate = true;
    private Integer quietPeriod;

    @DataBoundConstructor
    public BuildTriggerStep(String job) {
        this.job = job;
    }

    public String getJob() {
        return job;
    }

    public List<ParameterValue> getParameters() {
        return parameters;
    }

    @DataBoundSetter public void setParameters(List<ParameterValue> parameters) {
        this.parameters = parameters;
    }

    public boolean getWait() {
        return wait;
    }

    @DataBoundSetter public void setWait(boolean wait) {
        this.wait = wait;
    }

    public Integer getQuietPeriod() {
        return quietPeriod;
    }

    @DataBoundSetter public void setQuietPeriod(Integer quietPeriod) {
        this.quietPeriod = quietPeriod;
    }

    public boolean isPropagate() {
        return propagate;
    }

    @DataBoundSetter public void setPropagate(boolean propagate) {
        this.propagate = propagate;
    }

    @Extension
    public static class DescriptorImpl extends AbstractStepDescriptorImpl implements CustomDescribableModel {

        public DescriptorImpl() {
            super(BuildTriggerStepExecution.class);
        }

        // Note: This is necessary because the JSON format of the parameters produced by config.jelly when
        // using the snippet generator does not match what would be neccessary for databinding to work automatically.
        // Only called via the snippet generator.
        @Override public Step newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            BuildTriggerStep step = (BuildTriggerStep) super.newInstance(req, formData);
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

        /**
         * Compatibility hack for JENKINS-62305. Only affects runtime behavior of the step, not the snippet generator.
         * Ideally, password parameters would not be used at all with this step, but there was no documentation or
         * runtime warnings for this usage previously and so it is relatively common.
         */
        @Override
        public Map<String, Object> customInstantiate(Map<String, Object> map) {
            if (DescribableModel.of(PasswordParameterValue.class).getParameter("value").getErasedType() != Secret.class) {
                return map;
            }
            return copyMapReplacingEntry(map, "parameters", List.class, parameters -> parameters.stream()
                    .map(parameter -> {
                        if (parameter instanceof UninstantiatedDescribable) {
                            UninstantiatedDescribable ud = (UninstantiatedDescribable) parameter;
                            if (ud.getSymbol().equals("password")) {
                                Map<String, Object> newArguments = copyMapReplacingEntry(ud.getArguments(), "value", String.class, Secret::fromString);
                                return ud.withArguments(newArguments);
                            }
                        }
                        return parameter;
                    })
                    .collect(Collectors.toList())
            );
        }

        @Override
        public UninstantiatedDescribable customUninstantiate(UninstantiatedDescribable step) {
            Map<String, Object> newStepArgs = copyMapReplacingEntry(step.getArguments(), "parameters", List.class, parameters -> parameters.stream()
                    .map(parameter -> {
                        if (parameter instanceof UninstantiatedDescribable) {
                            UninstantiatedDescribable ud = (UninstantiatedDescribable) parameter;
                            if (ud.getSymbol().equals("password")) {
                                Map<String, Object> newParamArgs = copyMapReplacingEntry(ud.getArguments(), "value", Secret.class, Secret::getPlainText);
                                return ud.withArguments(newParamArgs);
                            }
                        }
                        return parameter;
                    })
                    .collect(Collectors.toList())
            );
            return step.withArguments(newStepArgs);
        }

        /**
         * Copy a map, replacing the entry with the specified key if it matches the specified type.
         */
        private static <T> Map<String, Object> copyMapReplacingEntry(Map<String, ?> map, String keyToReplace, Class<T> requiredValueType, Function<T, Object> replacer) {
            Map<String, Object> newMap = new HashMap<>();
            for (Map.Entry<String, ?> entry : map.entrySet()) {
                if (entry.getKey().equals(keyToReplace) && requiredValueType.isInstance(entry.getValue())) {
                    newMap.put(entry.getKey(), replacer.apply(requiredValueType.cast(entry.getValue())));
                } else {
                    newMap.put(entry.getKey(), entry.getValue());
                }
            }
            return newMap;
        }

        @Override
        public String getFunctionName() {
            return "build";
        }

        @Override
        public String getDisplayName() {
            return "Build a job";
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

            if (container == null || container == Jenkins.getInstanceOrNull()) {
                new Visitor("").onItemGroup(Jenkins.getInstanceOrNull());
            } else {
                new Visitor("").onItemGroup(container);
                if (value.startsWith("/"))
                    new Visitor("/").onItemGroup(Jenkins.getInstanceOrNull());

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

        public FormValidation doCheckPropagate(@QueryParameter boolean value, @QueryParameter boolean wait) {
            if (!value && !wait) {
                return FormValidation.warningWithMarkup(Messages.BuildTriggerStep_explicitly_disabling_both_propagate_and_wait());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckWait(@AncestorInPath ItemGroup<?> context, @QueryParameter boolean value, @QueryParameter String job) {
            if (!value) {
                return FormValidation.ok();
            }
            Item item = Jenkins.get().getItem(job, context, Item.class);
            if (item == null) {
                return FormValidation.ok();
            }
            if (item instanceof Job) {
                return FormValidation.ok();
            }
            return FormValidation.error(Messages.BuildTriggerStep_no_wait_for_non_jobs());
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
                return FormValidation.error(Messages.BuildTriggerStep_unsupported(((Describable)item).getDescriptor().getDisplayName()));
            }
            return FormValidation.error(Messages.BuildTriggerStep_unsupported(item.getClass().getName()));
        }

    }
}
