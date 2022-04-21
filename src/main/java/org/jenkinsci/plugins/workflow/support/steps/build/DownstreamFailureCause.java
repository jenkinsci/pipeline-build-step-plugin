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
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.CauseOfInterruption;

/**
 * Indicates that an upstream build failed because of a downstream build’s status.
 */
public final class DownstreamFailureCause extends CauseOfInterruption {

    private static final long serialVersionUID = 1;

    private final String id;

    DownstreamFailureCause(Run<?, ?> downstream) {
        id = downstream.getExternalizableId();
    }

    public @CheckForNull Run<?, ?> getDownstreamBuild() {
        return Run.fromExternalizableId(id);
    }

    @Override public void print(TaskListener listener) {
        String description;
        Run<?, ?> downstream = getDownstreamBuild();
        if (downstream != null) {
            // encodeTo(Run) calls getDisplayName, which does not include the project name.
            description = ModelHyperlinkNote.encodeTo("/" + downstream.getUrl(), downstream.getFullDisplayName()) + " completed with status " + downstream.getResult() + " (propagate: false to ignore)";
        } else {
            description = "Downstream build was not stable (propagate: false to ignore)";
        }
        listener.getLogger().println(description);
    }

    @Override public String getShortDescription() {
        Run<?, ?> downstream = getDownstreamBuild();
        if (downstream != null) {
            return downstream.getFullDisplayName() + " completed with status " + downstream.getResult() + " (propagate: false to ignore)";
        } else {
            return "Downstream build was not stable (propagate: false to ignore)";
        }
    }

}
