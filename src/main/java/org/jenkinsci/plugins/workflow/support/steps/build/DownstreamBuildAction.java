package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.InvisibleAction;
import hudson.model.Item;
import hudson.model.Run;
import org.springframework.security.access.AccessDeniedException;

/**
 * Tracks the downstream build triggered by the {@code build} step.
 * <p>Note that {@link #getBuildNumber} may return null if the build is in the queue or gets cancelled from the queue.
 * <p>Keep in mind that {@link #getBuild} may need to load the build, and so should be used carefully.
 *
 * @see BuildUpstreamCause
 * @see BuildUpstreamNodeAction
 */
public class DownstreamBuildAction extends InvisibleAction {
    private final String jobFullName;
    private Integer buildNumber;
    private String buildId;

    public DownstreamBuildAction(Item job) {
        this.jobFullName = job.getFullName();
    }

    public @NonNull String getJobFullName() {
        return jobFullName;
    }

    public @CheckForNull Integer getBuildNumber() {
        return buildNumber;
    }

    /**
     * Load the downstream build, if it has started and still exists.
     * <p>Loading builds indiscriminately will affect controller performance, so use this carefully. If you only need
     * to know whether the build started at one point, use {@link #getBuildNumber}.
     * @throws AccessDeniedException as per {@link ItemGroup#getItem}
     */
    public @CheckForNull Run<?, ?> getBuild() {
        if (buildId == null) {
            return null;
        }
        return Run.fromExternalizableId(buildId);
    }

    void setBuild(Run<?, ?> build) {
        this.buildNumber = build.getNumber();
        this.buildId = build.getExternalizableId();
    }
}
