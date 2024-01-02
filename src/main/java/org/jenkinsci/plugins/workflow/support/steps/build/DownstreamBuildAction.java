package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.InvisibleAction;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.springframework.security.access.AccessDeniedException;

/**
 * Tracks downstream builds triggered by the {@code build} step, as well as the {@link FlowNode#getId} of the step.
 *
 * @see BuildUpstreamCause
 */
public final class DownstreamBuildAction extends InvisibleAction {
    private final List<DownstreamBuild> downstreamBuilds = new ArrayList<>();

    public static @NonNull DownstreamBuild getOrCreate(@NonNull Run<?, ?> run, @NonNull String flowNodeId,
                                                       @NonNull String flowNodeName, @NonNull Item job) {
        DownstreamBuildAction downstreamBuildAction;
        synchronized (DownstreamBuildAction.class) {
            downstreamBuildAction = run.getAction(DownstreamBuildAction.class);
            if (downstreamBuildAction == null) {
                downstreamBuildAction = new DownstreamBuildAction();
                run.addAction(downstreamBuildAction);
            }
        }
        return downstreamBuildAction.getOrAddDownstreamBuild(flowNodeId, flowNodeName, job);
    }

    public synchronized @NonNull List<DownstreamBuild> getDownstreamBuilds() {
        return Collections.unmodifiableList(new ArrayList<>(downstreamBuilds));
    }

    private synchronized @NonNull DownstreamBuild getOrAddDownstreamBuild(@NonNull String flowNodeId,
                                                                          @NonNull String flowNodeName,
                                                                          @NonNull Item job) {
        for (DownstreamBuild build : downstreamBuilds) {
            if (build.getFlowNodeId().equals(flowNodeId)) {
                return build;
            }
        }
        var build = new DownstreamBuild(flowNodeId, flowNodeName, job);
        downstreamBuilds.add(build);
        return build;
    }

    public static final class DownstreamBuild {
        private final String flowNodeId;
        private final String flowNodeName;
        private final String jobFullName;
        private Integer buildNumber;

        DownstreamBuild(@NonNull String flowNodeId, @NonNull String flowNodeName, @NonNull Item job) {
            this.flowNodeId = flowNodeId;
            this.flowNodeName = flowNodeName;
            this.jobFullName = job.getFullName();
        }

        public @NonNull String getFlowNodeId() {
            return flowNodeId;
        }

        public @NonNull String getFlowNodeName() {
            return jobFullName;
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
}
