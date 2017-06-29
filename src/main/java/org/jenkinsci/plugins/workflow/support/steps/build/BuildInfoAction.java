package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.Job;
import hudson.model.Run;
import jenkins.model.Jenkins;
import hudson.model.InvisibleAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;


public class BuildInfoAction extends InvisibleAction {
    private final Queue<BuildInfo> buildInfos = new ConcurrentLinkedQueue<>();

    BuildInfoAction() {
    }

    BuildInfoAction(String projectName, int buildNumber) {
        buildInfos.add(new BuildInfo(projectName, buildNumber));
    }

    public List<Run<?, ?>> getChildBuilds() {
        List<Run<?, ?>> builds = new ArrayList<>();

        for (BuildInfo buildInfo : buildInfos) {
            if (buildInfo != null) {
                Job job = Jenkins.getActiveInstance().getItemByFullName(buildInfo.getProjectName(), Job.class);
                if (buildInfo.getBuildNumber() != 0) {
                    builds.add((job != null) ? job.getBuildByNumber(buildInfo.getBuildNumber()) : null);
                }
            }
        }

        return builds;
    }

    public void addBuildInfo(String projectName, int buildNumber) {
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