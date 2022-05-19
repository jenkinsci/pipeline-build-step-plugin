# Pipeline: Build Step Plugin

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/pipeline-build-step)](https://plugins.jenkins.io/pipeline-build-step)
[![Changelog](https://img.shields.io/github/v/tag/jenkinsci/pipeline-build-step-plugin?label=changelog)](https://github.com/jenkinsci/pipeline-build-step-plugin/blob/master/CHANGELOG.md)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/pipeline-build-step?color=blue)](https://plugins.jenkins.io/pipeline-build-step)

## Introduction

This plugin helps to add the pipeline `build` step, which triggers builds of other jobs.
Use the [_"Pipeline Syntax" Snippet Generator_](https://jenkins.io/redirect/pipeline-snippet-generator) to get a detailed example for your build step.
The Pipeline Syntax Snippet Generator helps the user generate steps for Jenkins pipeline.

## Features

The following features are available in Pipeline:

##### Project to Build
This is the name or path of a downstream job to build. It may be another Pipeline job, but more commonly a freestyle or other project. Use a simple name if the job is in the same folder as this upstream Pipeline job; otherwise can use relative paths like `../sister-folder/downstream` or absolute paths like `/top-level-folder/nested-folder/downstream`. Example;
```groovy
  job: 'Hello-World'
```

##### Wait for completion
You may ask that this Pipeline build wait for completion of the downstream build. In that case the return value of the step is an object on which you can obtain read-only properties: so you can inspect its properties such as result using `.result` and so on.
Properties of build upon completion are;
* getBuildCauses: Returns a JSON array of build causes for the current build
```groovy
  script {
    def buildResult = build job: 'Downstream'
    echo "Build getBuildCauses : ${buildResult.getBuildCauses}"
  }
```
* number: This returns a build number (integer). Example; 
```groovy
  script {
    def buildResult = build job: 'Downstream'
    echo "Build Number : ${buildResult.number}"
  }
```
* result typically SUCCESS, UNSTABLE, or FAILURE (may be null for an ongoing build). Example;
```groovy
  script {
    def buildResult = build job: 'Downstream'
    echo "Build Result : ${buildResult.result}"
  }
```
* currentResult: typically SUCCESS, UNSTABLE, or FAILURE. Will never be null. Example;

```groovy
  script {
    def buildResult = build job: 'Downstream'
    echo "Build Current Result : ${buildResult.currentResult}"
  }
```

* resultIsBetterOrEqualTo(String): Compares the current build result to the provided result string (SUCCESS, UNSTABLE, or FAILURE) and returns true if the current build result is better than or equal to the provided result.
```groovy
  script {
    def buildResult = build job: 'Downstream'
    echo "Build Result is Better: ${buildResult.resultIsBetterOrEqualTo("SUCCESS")}"
  }
```

* resultIsWorseOrEqualTo(String): Compares the current build result to the provided result string (SUCCESS, UNSTABLE, or FAILURE) and returns true if the current build result is worse than or equal to the provided result.
* displayName: normally #123 but sometimes set to, e.g., an SCM commit identifier.
* fullDisplayName: normally folder1 » folder2 » foo #123.
* projectName: Name of the project of this build, such as foo.
* fullProjectName: Full name of the project of this build, including folders such as folder1/folder2/foo.
* description: additional information about the build.
* id: normally number as a string.
* timeInMillis: time since the epoch when the build was scheduled.
* startTimeInMillis: time since the epoch when the build started running.
* duration: duration of the build in milliseconds.
* durationString: a human-readable representation of the build duration.
* previousBuild: previous build of the project, or null.
* previousBuildInProgress: previous build of the project that is currently building, or null.
* previousBuiltBuild: previous build of the project that has been built (may be currently building), or null.
* previousCompletedBuild: previous build of the project that has last finished building, or null.
* previousFailedBuild: previous build of the project that has last failed to build, or null.
* previousNotFailedBuild: previous build of the project that did not fail to build (eg. result is successful or unstable), or null.
* previousSuccessfulBuild: previous build of the project that has successfully built, or null.
* nextBuild: next build of the project, or null.
* absoluteUrl: URL of build index page.
* buildVariables: for a non-Pipeline downstream build, offers access to a map of defined build variables; for a Pipeline downstream build, any variables set globally on env at the time the build ends. Child Pipeline jobs can use this to report additional information to the parent job by setting variables in env. Note that build parameters are not shown in buildVariables.
* changeSets: a list of changesets coming from distinct SCM checkouts; each has a kind and is a list of commits; each commit has a commitId, timestamp, msg, author, and affectedFiles each of which has an editType and path; the value will not generally be Serializable, so you may only access it inside a method marked @NonCPS.
* upstreamBuilds: a list of upstream builds. These are the builds of the upstream projects whose artifacts feed into this build.
* rawBuild: a hudson.model.Run with further APIs, only for trusted libraries or administrator-approved scripts outside the sandbox; the value will not be Serializable, so you may only access it inside a method marked @NonCPS.
* keepLog: true if the log file for this build should be kept and not deleted.
  If you do not wait, this step succeeds so long as the downstream build can be added to the queue (it will not even have been started). In that case there is currently no return value.

##### Propagate error
If enabled (default state), then the result of this step is that of the downstream build (e.g., success, unstable, failure, not built, or aborted) i.e. if the downstream build fails then the upstream build fails also. If disabled, then this step succeeds even if the downstream build is unstable, failed, etc.; use the result property of the return value as needed i.e if the downstream build fails it does not affect that of the upstream build. Example;
```groovy
build propagate: false, job: 'Downstream'
```
---
**NOTE**

Explicitly disabling both propagate and wait is redundant, since propagate is ignored when wait is disabled. For brevity, leave propagate at its default.

---

##### Quiet Period
Optional alternate quiet period (in seconds) before building. If unset, defaults to the quiet period defined by the downstream project (or finally to the system-wide default quiet period).
Example;
```groovy
build quietPeriod: 3, job: 'Downstream'
```

##### Parameters
A list of parameters to pass to the downstream job. When passing secrets to downstream jobs, prefer credentials parameters over password parameters.
Example;
```groovy
build job: 'Downstream', parameters: [credentials('name', 'credentials-id')]
```

### Passing secret values to other jobs

The recommended approach to pass secret values using the `build` step is to use credentials parameters:

```groovy
build(job: 'foo', parameters: [credentials('parameter-name', 'credentials-id')])
```

See [the user guide for the Credentials Plugin](https://plugins.jenkins.io/credentials/) for a general overview of how credentials work in Jenkins and how they can be configured, and [the documentation for the Credentials Binding Plugin](https://plugins.jenkins.io/credentials-binding/) for an overview of how to access and use credentials from a Pipeline.

The `build` step also supports passing password parameters, but this is not recommended.
The plaintext secret may be persisted as part of the Pipeline's internal state, and it will not be automatically masked if it appears in the build log.
Here is an example for reference:

```groovy
build(job: 'foo', parameters: [password('parameter-name', 'secret-value')])
```

## Version History

See [the changelog](CHANGELOG.md).
