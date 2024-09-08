# Gradle Plugin for AWS CodeArtifact

[![Gradle Plugin Portal Version](https://img.shields.io/gradle-plugin-portal/v/io.cloudshiftdev.codeartifact?style=for-the-badge&cacheSeconds=900)](https://plugins.gradle.org/plugin/io.cloudshiftdev.codeartifact)

This plugin provides support for using AWS CodeArtifact Maven repositories as a Gradle repository.

Fetching of CodeArtifact tokens is handled by this plugin, with tokens being securely cached to reduce the number of
requests to AWS.

## Getting Started

1. Apply the plugin to the `settings.gradle.kts` script:

    ```kotlin
    plugins {
        id("io.cloudshiftdev.codeartifact") version "<latest>"
    }
    ```
2. Specify CodeArtifact repositories as required:

   In `settings.gradle.kts`:
    ```kotlin
    dependencyResolutionManagement {
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

### Via an AWS Profile:

| System Property      | Environment Variable | Description                        |
|----------------------|----------------------|------------------------------------|
| codeartifact.profile | CODEARTIFACT_PROFILE | The name of the AWS profile to use |
| aws.profile          | AWS_PROFILE          | The name of the AWS profile to use |

System properties can be provided in `gradle.properties`, e.g. `systemProp.codeartifact.profile=default`

### From credentials

| System Property                  | Environment Variable               | Description           |
|----------------------------------|------------------------------------|-----------------------|
| codeartifact.aws.accessKeyId     | CODEARTIFACT_AWS_ACCESS_KEY_ID     | AWS access key id     |
| codeartifact.aws.secretAccessKey | CODEARTIFACT_AWS_SECRET_ACCESS_KEY | AWS secret access key |
| codeartifact.aws.sessionToken    | CODEARTIFACT_AWS_SESSION_TOKEN     | AWS session token     |
| aws.accessKeyId                  | AWS_ACCESS_KEY_ID                  | AWS access key id     |
| aws.secretAccessKey              | AWS_SECRET_ACCESS_KEY              | AWS secret access key |
| aws.sessionToken                 | AWS_SESSION_TOKEN                  | AWS session token     |
| codeartifact.stsRoleArn          | CODEARTIFACT_STS_ROLE_ARN          | AWS role to assume    |

The plugin will use the default AWS credentials provider chain, which includes environment variables, system properties,
and IAM roles. If you need to use a specific profile or credentials file, you can specify them in
your `gradle.properties` file.

A profile name can also be specified as part of the repository URL:

    ```kotlin
    repositories {
        awsCodeArtifact(url = "https://<domain>-<owner>.d.codeartifact.<region>.amazonaws.com/maven/<repository>?codeartifact.profile=default")
    }
    ```

Assumption of a role is supported via `codeartifact.stsRoleArn` property or `CODEARTIFACT_STS_ROLE_ARN` environment
variable; setting either of these to a role ARN will cause the plugin to assume the specified role, using credentials
resolved by any of the other mechanisms described above.  When a role is assumed it uses an in-line scoped-down policy to
limit role permissions to those required for CodeArtifact.

## Other configuration properties

| System Property                                              | Environment Variable                                         | Description                                                                                 |
|--------------------------------------------------------------|--------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| codeartifact.domains                                         | CODEARTIFACT_DOMAINS                                         | Regex of domains to provide authentication for (defaults to all domains)                    |

## Advanced use

### As a Gradle plugin / custom Gradle distribution

This plugin supports being applied as a Gradle plugin as part of a custom Gradle distribution; it will then be able to provide secured CodeArtifact repositories for custom plugin resolution.

## Compatibility

This plugin requires Gradle 8.6 or later running on Java 17 or later and is compatible with the Gradle Configuration
Cache.  Only the Gradle Kotlin DSL is supported.

