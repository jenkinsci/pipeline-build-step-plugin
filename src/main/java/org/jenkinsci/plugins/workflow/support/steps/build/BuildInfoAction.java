package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.InvisibleAction;

import java.util.ArrayList;
import java.util.List;


public class BuildInfoAction extends InvisibleAction {
    private final List<BuildInfo> buildInfoList = new ArrayList<>();

    public BuildInfoAction(String projectName, int buildNumber) {
        buildInfoList.add(new BuildInfo(projectName, buildNumber));
    }

    public List<BuildInfo> getBuildInfoList() {
        return buildInfoList;
    }

    public void addBuildInfo(String projectName, int buildNumber) {
        buildInfoList.add(new BuildInfo(projectName, buildNumber));
    }

    static class BuildInfo {
        private final String projectName;
        private final int buildNumber;

        public BuildInfo(String projectName, int buildNumber) {
            this.projectName = projectName;
            this.buildNumber = buildNumber;
        }

        public String getProjectName() {
            return projectName;
        }

        public int getBuildNumber() {
            return buildNumber;
        }
    }
}