# Gradle Plugin for AWS CodeArtifact

[![Gradle Plugin Portal Version](https://img.shields.io/gradle-plugin-portal/v/io.cloudshiftdev.codeartifact?style=for-the-badge&cacheSeconds=900)](https://plugins.gradle.org/plugin/io.cloudshiftdev.codeartifact)

This plugin provides support for using AWS CodeArtifact Maven repositories as a Gradle repository.

Fetching of CodeArtifact tokens is handled by this plugin, with tokens being securely cached to reduce the number of requests to AWS.

> [!NOTE]
> :rocket: This plugin uses a modern HttpClient, enabling HTTP/2 support and compression (for compressible  content, such as POM files, where your CDN supports that).<br/>This offers a considerable performance improvement. :rocket:

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

| System Property                                                       | Environment Variable                                                   | Description                                                                                 |
|-----------------------------------------------------------------------|------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| codeartifact.domains                                                  | CODEARTIFACT_DOMAINS                                                   | Regex of domains to provide authentication for (defaults to all domains)                    |
 codeartifact.proxy.enabled                                            | CODEARTIFACT_PROXY_ENABLED                                             | Enable proxying of CodeArtifact URLs (default: true), if proxy base URLs configured (above) |
| codeartifact.&lt;domain>-&lt;domain owner>-&lt;region>.proxy.base-url | CODEARTIFACT_&lt;DOMAIN>_&lt;DOMAIN_OWNER>\_&lt;REGION>_PROXY_BASE_URL | Proxy base URL to use                       |
| codeartifact.&lt;region>.proxy.base-url                               | CODEARTIFACT_&lt;REGION>_PROXY_BASE_URL                                | Proxy base URL to use                       |
| codeartifact.proxy.base-url                                           | CODEARTIFACT_PROXY_BASE_URL                                            | Proxy base URL to use                       |

## Reverse Proxy Configuration

If you wish to place CodeArtifact behind a reverse proxy (for example, CloudFront or other CDN) you can specify the proxy to use (see table above).  The configured CodeArtifact URLs are used to generate a CodeArtifact token, and are then reconfigured to use the proxy URL (continuing to pass along the authentication token).  Your configured proxy is responsible for handling authentication/caching as appropriate.

Repositories configured for publishing are not configured to use the proxy.

## Setting up CloudFront with CodeArtifact

CloudFront can be used as a content delivery network in front of CloudFront to optimize downloads for distributed teams.

Here is an example of a CloudFront distribution configuration via AWS CDK:

```typescript
import {Construct} from 'constructs';
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront';
import {HttpVersion} from 'aws-cdk-lib/aws-cloudfront';
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins';
import {CodeArtifactResources} from "../certificate-stack";
import * as route53 from 'aws-cdk-lib/aws-route53';
import * as targets from 'aws-cdk-lib/aws-route53-targets';
import {Duration} from "aws-cdk-lib";
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as path from 'path';

export interface CdnProps {
    codeArtifactResources: CodeArtifactResources,
    originDomainName: string,
}

/**
 * Creates a CloudFront distribution and supporting resources in front of CodeArtifact.
 * Handles authentication using the CodeArtifact token.
 */
export class Cdn extends Construct {
    constructor(scope: Construct, id: string, props: CdnProps) {
        super(scope, id);

        const edgeFn = new cloudfront.experimental.EdgeFunction(this, 'ViewerAuthFn', {
            runtime: lambda.Runtime.NODEJS_18_X,
            handler: 'index.handler',
            code: lambda.Code.fromAsset(path.join(__dirname, 'auth-lambda')),  // lambda/index.js
            memorySize: 128,
            timeout: Duration.seconds(5),
        });

        // CodeArtifact emits Cache-Control headers for resources that are cacheable (assets), but does not
        // emit cache headers for those that aren't (metadata).
        // set the default cache TTL to 0 (do not cache)
        const cachingOnlyIfHeaderPresent = new cloudfront.CachePolicy(this, 'CachingIfHeaderPresent', {
            cachePolicyName: 'CachingOnlyIfHeaderPresent',
            comment: 'CachingOptimized defaulting to not caching if no Cache-Control header provided',

            minTtl: Duration.seconds(0),
            defaultTtl: Duration.seconds(0),
            maxTtl: Duration.days(365),

            cookieBehavior: cloudfront.CacheCookieBehavior.none(),
            headerBehavior: cloudfront.CacheHeaderBehavior.none(),
            queryStringBehavior: cloudfront.CacheQueryStringBehavior.none(),

            enableAcceptEncodingBrotli: true,
            enableAcceptEncodingGzip: true,
        });

        const cdn = new cloudfront.Distribution(this, 'Distribution', {
            enableLogging: true,
            defaultBehavior: {
                origin: new origins.HttpOrigin(props.originDomainName, {
                    protocolPolicy: cloudfront.OriginProtocolPolicy.HTTPS_ONLY,
                    originPath: '/',
                    originSslProtocols: [cloudfront.OriginSslPolicy.TLS_V1_2]
                }),
                originRequestPolicy: cloudfront.OriginRequestPolicy.ALL_VIEWER_EXCEPT_HOST_HEADER,
                cachePolicy: cachingOnlyIfHeaderPresent,
                viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.HTTPS_ONLY,
                allowedMethods: cloudfront.AllowedMethods.ALLOW_GET_HEAD,
                compress: true,
                edgeLambdas: [{
                    functionVersion: edgeFn.currentVersion,
                    eventType: cloudfront.LambdaEdgeEventType.VIEWER_REQUEST,
                    includeBody: false,
                }],
            },
            domainNames: [props.codeArtifactResources.cdnDomainName],
            certificate: props.codeArtifactResources.cdnCertificate,
            httpVersion: HttpVersion.HTTP2_AND_3,
            enableIpv6: true,
            minimumProtocolVersion: cloudfront.SecurityPolicyProtocol.TLS_V1_2_2021,
        });

        new route53.ARecord(this, 'AliasRecordV4', {
            zone: props.codeArtifactResources.zone,
            recordName: props.codeArtifactResources.cdnDomainName,
            target: route53.RecordTarget.fromAlias(new targets.CloudFrontTarget(cdn)),
        });

        new route53.AaaaRecord(this, 'AliasRecordV6', {
            zone: props.codeArtifactResources.zone,
            recordName: props.codeArtifactResources.cdnDomainName,
            target: route53.RecordTarget.fromAlias(new targets.CloudFrontTarget(cdn)),
        });
    }
}
```

## Obtaining CodeArtifact tokens for other (non-Maven repository) uses

If you wish to use CodeArtifact tokens elsehwere, for example configuring `.npmrc` for CodeArtifact npm repositories, you can obtain a token provider using `ProviderFactory.codeArtifactToken(endpoint)`.

## Advanced use

### As a Gradle plugin / custom Gradle distribution

This plugin supports being applied as a Gradle plugin as part of a custom Gradle distribution; it will then be able to provide secured CodeArtifact repositories for custom plugin resolution.

## Compatibility

This plugin requires Gradle 8.6 or later running on Java 17 or later and is compatible with the Gradle Configuration
Cache.  Only the Gradle Kotlin DSL is supported.

