package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.ItemGroup;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class WaitForBuildStep extends Step {

    private final String runId;
    private boolean propagate = false;
    private boolean propagateAbort = false;

    @DataBoundConstructor
    public WaitForBuildStep(String runId) {
        this.runId = runId;
    }

    public String getRunId() {
        return runId;
    }

    public boolean isPropagate() {
        return propagate;
    }

    @DataBoundSetter public void setPropagate(boolean propagate) {
        this.propagate = propagate;
    }

    public boolean isPropagateAbort() {
        return propagateAbort;
    }

    @DataBoundSetter public void setPropagateAbort(boolean propagateAbort) {
        this.propagateAbort = propagateAbort;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new WaitForBuildStepExecution(this, context);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "waitForBuild";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Wait for build to complete";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            Set<Class<?>> context = new HashSet<>();
            Collections.addAll(context, FlowNode.class, Run.class, TaskListener.class);
            return Collections.unmodifiableSet(context);
        }
    }

    @SuppressWarnings("rawtypes")
    public FormValidation doCheckRunId(@AncestorInPath ItemGroup<?> context, @QueryParameter String value) {
        if (value.isEmpty()) {
            return FormValidation.warning(Messages.WaitForBuildStep_no_run_configured());
        }
        Run run = Run.fromExternalizableId(value);
        if (run == null) {
            return FormValidation.error(Messages.WaitForBuildStep_cannot_find(value));
        }
        return FormValidation.ok();
    }
}
