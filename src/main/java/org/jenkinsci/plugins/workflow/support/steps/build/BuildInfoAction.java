package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.InvisibleAction;
import hudson.model.Job;
import hudson.model.Run;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BuildInfoAction extends InvisibleAction {
    private final Queue<BuildInfo> buildInfos = new ConcurrentLinkedQueue<>();

    BuildInfoAction(String projectName, int buildNumber) {
        buildInfos.add(new BuildInfo(projectName, buildNumber));
    }

    public List<Run<?, ?>> getChildBuilds() {
        List<Run<?, ?>> builds = new ArrayList<>(buildInfos.size());

        for (BuildInfo buildInfo : buildInfos) {
            if (buildInfo != null) {
                Run run = getBuildByNameAndNumber(buildInfo.getProjectName(), buildInfo.getBuildNumber());
                if (run != null) {
                    builds.add(run);
                }
            }
        }

        return builds;
    }

    private Run getBuildByNameAndNumber(String jobName, int buildNumber) {
        Job job = Jenkins.getActiveInstance().getItemByFullName(jobName, Job.class);
        if (job != null) {
            return job.getBuildByNumber(buildNumber);
        }
        return null;
    }

    void addBuildInfo(String projectName, int buildNumber) {
        buildInfos.add(new BuildInfo(projectName, buildNumber));
    }

    private static class BuildInfo {
        private final String projectName;
        private final int buildNumber;

        BuildInfo(String projectName, int buildNumber) {
            this.projectName = projectName;
            this.buildNumber = buildNumber;
        }

        String getProjectName() {
            return projectName;
        }

        int getBuildNumber() {
            return buildNumber;
        }
    }
}