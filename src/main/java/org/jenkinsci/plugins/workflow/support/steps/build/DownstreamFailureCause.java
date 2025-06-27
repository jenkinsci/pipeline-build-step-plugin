/*
 * The MIT License
 *
 * Copyright 2019 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;

/**
 * Indicates that an upstream build failed because of a downstream build’s status.
 */
public final class DownstreamFailureCause extends CauseOfInterruption {

    private static final long serialVersionUID = 1;

    private final String jobFullName;
    private final int buildNumber;
    private final String url;
    private final Result result;
    // Only set on deserialized causes from before the above fields were added.
    @Deprecated
    private String id;

    DownstreamFailureCause(Run<?, ?> downstream) {
        jobFullName = downstream.getParent().getFullName();
        buildNumber = downstream.getNumber();
        url = downstream.getUrl();
        result = downstream.getResult();
    }

    public @CheckForNull Run<?, ?> getDownstreamBuild() {
        if (id != null) {
            return Run.fromExternalizableId(id);
        }
        Job<?, ?> job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        return job != null ? job.getBuildByNumber(buildNumber) : null;
    }

    @Override public void print(TaskListener listener) {
        String description;
        if (id != null) {
            Run<?, ?> downstream = getDownstreamBuild();
            if (downstream != null) {
                // encodeTo(Run) calls getDisplayName, which does not include the project name.
                description = description(ModelHyperlinkNote.encodeTo("/" + downstream.getUrl(), downstream.getFullDisplayName()), downstream.getResult());
            } else {
                description = "Downstream build was not stable (propagate: false to ignore)";
            }
        } else {
            description = description(ModelHyperlinkNote.encodeTo("/" + url, jobFullName + " #" + buildNumber), result);
        }
        listener.getLogger().println(description);
    }

    @Override public String getShortDescription() {
        if (id != null) {
            Run<?, ?> downstream = getDownstreamBuild();
            if (downstream != null) {
                return description(downstream.getFullDisplayName(), downstream.getResult());
            } else {
                return "Downstream build was not stable (propagate: false to ignore)";
            }
        } else {
            return description(jobFullName + " #" + buildNumber, result);
        }
    }

    private String description(String downstreamBuildDescription, Result downstreamResult) {
        return downstreamBuildDescription + " completed with status " + downstreamResult + " (propagate: false to ignore)";
    }

}
