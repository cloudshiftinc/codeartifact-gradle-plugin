# Gradle Plugin for AWS CodeArtifact

This plugin provides support for using AWS CodeArtifact Maven reposistories as a Gradle repository.  

Fetching of CodeArtifact tokens is handled by this plugin, with tokens being securely cached to reduce the number of requests to AWS.

## Getting Started

1. Apply the plugin to you `settings.gradle.kts` script:

```kotlin
plugins {
    id("io.cloudshiftdev.codeartifact")
}
```
2. Specify your CodeArtifact repositories as required:

In `settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        awsCodeArtifact(url = "https://<domain>-<owner>.d.codeartifact.<region>.amazonaws.com/maven/<repository>")
    }
}
```

For publishing, in `build.gradle.kts`:
```kotlin
publishing {
    repositories {
        awsCodeArtifact(url = "https://<domain>-<owner>.d.codeartifact.<region>.amazonaws.com/maven/<repository>")
    }
}
```
The `awsCodeArtifact` extension function can be used almost anywhere you can specify a repository in Gradle.

3. Pass AWS credentials to your build:

### From an AWS Profile:

| System Property      | Environment Variable |Description|
|----------------------|----------------------|---|
| codeartifact.profile | CODEARTIFACT_PROFILE |The name of the AWS profile to use|
| aws.profile          | AWS_PROFILE          |The name of the AWS profile to use|

System properties can be provided in `gradle.properties`, e.g. `systemProp.codeartifact.profile=default`

### From credentials

| System Property                  | Environment Variable               | Description          |
|----------------------------------|------------------------------------|----------------------|
| codeartifact.aws.accessKeyId     | CODEARTIFACT_AWS_ACCESS_KEY_ID     | AWS access key id    |
| codeartifact.aws.secretAccessKey | CODEARTIFACT_AWS_SECRET_ACCESS_KEY | AWS secretaccess key |
| codeartifact.aws.sessionToken    | CODEARTIFACT_AWS_SESSION_TOKEN     | AWS session token    |
| aws.accessKeyId                  | AWS_ACCESS_KEY_ID     | AWS access key id    |
| aws.secretAccessKey              | AWS_SECRET_ACCESS_KEY | AWS secretaccess key |
| aws.sessionToken                | AWS_SESSION_TOKEN     | AWS session token    |


The plugin will use the default AWS credentials provider chain, which includes environment variables, system properties, and IAM roles.  If you need to use a specific profile or credentials file, you can specify them in your `gradle.properties` file.

A profile name can also be specified as part of the repository URL:

```kotlin
repositories {
        awsCodeArtifact(url = "https://<domain>-<owner>.d.codeartifact.<region>.amazonaws.com/maven/<repository>?codeartifact.profile=default")
    }

```

