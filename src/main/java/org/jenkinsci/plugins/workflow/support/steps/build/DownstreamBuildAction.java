package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.InvisibleAction;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.actions.PersistentAction;
import org.springframework.security.access.AccessDeniedException;

/**
 * Tracks the downstream build triggered by the {@code build} step.
 *
 * @see BuildUpstreamCause
 */
public final class DownstreamBuildAction extends InvisibleAction implements PersistentAction {
    private final String jobFullName;
    private final Integer buildNumber;

    DownstreamBuildAction(Item job) {
        this.jobFullName = job.getFullName();
        this.buildNumber = null;
    }

    DownstreamBuildAction(Run<?, ?> run) {
        this.jobFullName = run.getParent().getFullName();
        this.buildNumber = run.getNumber();
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
}
