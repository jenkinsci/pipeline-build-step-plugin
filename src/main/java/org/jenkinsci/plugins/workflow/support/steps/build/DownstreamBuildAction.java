package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.InvisibleAction;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.springframework.security.access.AccessDeniedException;

/**
 * Tracks a downstream build triggered by the {@code build} step, as well as the {@link FlowNode#getId} of the step.
 *
 * @see BuildUpstreamCause
 */
public final class DownstreamBuildAction extends InvisibleAction {
    private final String flowNodeId;
    private final String jobFullName;
    private Integer buildNumber;

    DownstreamBuildAction(@NonNull String flowNodeId, @NonNull Item job) {
        this.flowNodeId = flowNodeId;
        this.jobFullName = job.getFullName();
    }

    public @NonNull String getFlowNodeId() {
        return flowNodeId;
    }

    public @NonNull String getJobFullName() {
        return jobFullName;
    }

    /**
     * Get the build number of the downstream build, or {@code null} if the downstream build has not yet started or the queue item was cancelled.
     */
    public @CheckForNull Integer getBuildNumber() {
        return buildNumber;
    }

    /**
     * Load the downstream build, if it has started and still exists.
     * <p>Loading builds indiscriminately will affect controller performance, so use this carefully. If you only need
     * to know whether the build started at one point, use {@link #getBuildNumber}.
     * @throws AccessDeniedException as per {@link ItemGroup#getItem}
     */
    public @CheckForNull Run<?, ?> getBuild() throws AccessDeniedException {
        if (buildNumber == null) {
            return null;
        }
        return Run.fromExternalizableId(jobFullName + '#' + buildNumber);
    }

    void setBuild(@NonNull Run<?, ?> run) {
        this.buildNumber = run.getNumber();
    }
}
