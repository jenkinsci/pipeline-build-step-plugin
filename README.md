# Pipeline: Build Step Plugin

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/pipeline-build-step)](https://plugins.jenkins.io/pipeline-build-step)
[![Changelog](https://img.shields.io/github/v/tag/jenkinsci/pipeline-build-step-plugin?label=changelog)](https://github.com/jenkinsci/pipeline-build-step-plugin/blob/master/CHANGELOG.md)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/pipeline-build-step?color=blue)](https://plugins.jenkins.io/pipeline-build-step)

## Introduction

Adds the Pipeline `build` step, which triggers builds of other jobs.

### Passing secret values to other jobs

The recommended approach to pass secret values using the `build` step is to use credentials parameters:

```
build(job: 'foo', parameters: [credentials('parameter-name', 'credentials-id')])
```

See [the user guide for the Credentials Plugin](https://plugins.jenkins.io/credentials/) for a general overview of how credentials work in Jenkins and how they can be configured, and [the documentation for the Credentials Binding Plugin](https://plugins.jenkins.io/credentials-binding/) for an overview of how to access and use credentials from a Pipeline.

The `build` step also supports passing password parameters, but this is not recommended.
The plaintext secret may be persisted as part of the Pipeline's internal state, and it will not be automatically masked if it appears in the build log.
Here is an example for reference:

```
build(job: 'foo', parameters: [password('parameter-name', 'secret-value')])
```

## Version History

See [the changelog](CHANGELOG.md).