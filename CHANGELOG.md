## Changelog

### 2.11

Release date: 2020-01-03

-   Fix: Mark that the `FlowInterruptedException` thrown by the `build` step when the downstream build fails while using `propagate: true` should not be treated as a build interruption. Part of the fix for [JENKINS-60354](https://issues.jenkins-ci.org/browse/JENKINS-60354). Update Pipeline: Basic Steps Plugin to 2.19 or newer along with this update for the full fix. ([PR 39](https://github.com/jenkinsci/pipeline-build-step-plugin/pull/39))
-   Internal: Update parent POM. ([PR 40](https://github.com/jenkinsci/pipeline-build-step-plugin/pull/40))

### 2.10

Release date: 2019-11-26

-   Fix: When using `propagate: true` (the default), the result of the downstream job is now used as the result of the `build` step. Previously, when the downstream job was not successful, the result of the build step was `FAILURE` no matter the actual result (`UNSTABLE`, `ABORTED`, etc.). [JENKINS-49073](https://issues.jenkins-ci.org/browse/JENKINS-49073).

    **Note**: As a result of this change, you are advised to also update Pipeline: Groovy Plugin to version 2.77 (or newer), so that the result of parallel steps when not using `failFast: true` is the worst result of all branches, rather than the result of the first completed branch. The distinction between these behaviors is more likely to be encountered now that the build step can have results other than  `SUCCESS` and `FAILURE`.
-   Improvement: When the type of a parameter passed to the `build` step does not match the type of the same parameter on the downstream job, the passed parameter will now be automatically converted to the correct type in some cases (such as when passing a string parameter when the downstream job expects a password parameter). ([JENKINS-60216](https://issues.jenkins-ci.org/browse/JENKINS-60216))
-   Improvement: Document the default value of the `propagate` option for the `build` step. ([PR 25](https://github.com/jenkinsci/pipeline-build-step-plugin/pull/25))
-   Internal: Incrementalify the plugin, simplify code using new methods from recent versions of Jenkins core, replace usages of deprecated APIs, add additional tests, and improve existing tests. ([PR 27](https://github.com/jenkinsci/pipeline-build-step-plugin/pull/27), [PR 28](https://github.com/jenkinsci/pipeline-build-step-plugin/pull/28), [PR 29](https://github.com/jenkinsci/pipeline-build-step-plugin/pull/29), [PR 30](https://github.com/jenkinsci/pipeline-build-step-plugin/pull/30), [PR 31](https://github.com/jenkinsci/pipeline-build-step-plugin/pull/31), [PR 32](https://github.com/jenkinsci/pipeline-build-step-plugin/pull/32), [PR 33](https://github.com/jenkinsci/pipeline-build-step-plugin/pull/33))

### 2.9 

Release date: 2019-04-15

-   [JENKINS-52038](https://issues.jenkins-ci.org/browse/JENKINS-52038) -
    Reject invalid values for choice parameters.

### 2.8 

Release date: 2019-03-18

-   Internal: Update dependencies and fix resulting test failures so
    that the plugin's tests pass successfully when run using the PCT
    ([PR 20](https://github.com/jenkinsci/pipeline-build-step-plugin/pull/20),
    [PR 22](https://github.com/jenkinsci/pipeline-build-step-plugin/pull/22))

### 2.7 

Release date: 2018-01-24

-   [JENKINS-48632](https://issues.jenkins-ci.org/browse/JENKINS-48632) -
    ensure descriptions are included on downstream parameters.
-   [JENKINS-38339](https://issues.jenkins-ci.org/browse/JENKINS-38339) -
    Link downstream builds to the `FlowNode` that triggered them.

### 2.6 

Release date: 2017-11-06

-   [JENKINS-46934](https://issues.jenkins-ci.org/browse/JENKINS-46934) -
    Prevent possible deadlock when killing jobs using `build` step.

### 2.5.1 

Release date: 2017-07-10

-   [Fix security
    issue](https://jenkins.io/security/advisory/2017-07-10/)

### 2.5 

Release date: 2017-04-06

-   [JENKINS-38887](https://issues.jenkins-ci.org/browse/JENKINS-38887) `build`
    can now be used to trigger indexing of multibranch projects, rather
    than building regular jobs.

### 2.4 

Release date: 2016-11-21

-   In certain cases, interrupting a build running in a `build` step
    might not break you out of a loop.
-   Making sure interrupting a build running in a `build` step does
    something, even if Jenkins is unsure of the status of this step.
-   [JENKINS-39454](https://issues.jenkins-ci.org/browse/JENKINS-39454)
    Work around a core race condition that could result in hanging
    `build` steps when many are being run concurrently.

### 2.3 

Release date: 2016-09-23

-   [JENKINS-38114](https://issues.jenkins-ci.org/browse/JENKINS-38114)
    Unified help between the `currentBuild` global variable and the
    return value of `build` into the [Pipeline Supporting APIs
    Plugin](https://plugins.jenkins.io/workflow-support).
-   [JENKINS-37484](https://issues.jenkins-ci.org/browse/JENKINS-37484)
    Documentation fix: `FAILURE`, not `FAILED`, is the status name.

### 2.2 

Release date: 2016-07-11

-   Documentation for
    [JENKINS-30412](https://issues.jenkins-ci.org/browse/JENKINS-30412).
-   [JENKINS-31842](https://issues.jenkins-ci.org/browse/JENKINS-31842)
    Display status of a running `build` step in thread dumps.

### 2.1 

Release date: 2016-05-31

-   [JENKINS-28673](https://issues.jenkins-ci.org/browse/JENKINS-28673)
    `IllegalStateException` printed to log after deleting a downstream
    build.

### 2.0 

Release date: 2016-04-05

-   First release under per-plugin versioning scheme. See [1.x
    changelog](https://github.com/jenkinsci/workflow-plugin/blob/82e7defa37c05c5f004f1ba01c93df61ea7868a5/CHANGES.md)
    for earlier releases.
-   Includes the `build` step formerly in [Pipeline Supporting APIs
    Plugin](https://plugins.jenkins.io/workflow-support).
