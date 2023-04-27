package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.InvisibleAction;
import org.jenkinsci.plugins.workflow.steps.StepContext;

public class WaitForBuildAction  extends InvisibleAction {

    final StepContext context;
    final boolean propagate;

    WaitForBuildAction(StepContext context, boolean propagate) {
        this.context = context;
        this.propagate = propagate;
    }
}
