package io.cloudshiftdev.gradle.codeartifact

import aws.sdk.kotlin.runtime.auth.credentials.AssumeRoleParameters
import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProviderConfigurationException
import aws.sdk.kotlin.runtime.auth.credentials.StsAssumeRoleCredentialsProvider
import aws.sdk.kotlin.services.codeartifact.CodeartifactClient
import aws.sdk.kotlin.services.codeartifact.getAuthorizationToken
import aws.sdk.kotlin.services.codeartifact.model.PackageFormat
import aws.sdk.kotlin.services.codeartifact.publishPackageVersion
import aws.smithy.kotlin.runtime.auth.awscredentials.CachedCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProviderChain
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.content.asByteStream
import aws.smithy.kotlin.runtime.time.toJvmInstant
import kotlin.time.Duration.Companion.hours
import kotlin.time.measureTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import org.gradle.api.logging.Logging

internal object CodeArtifactOperations {
    private val logger = Logging.getLogger(CodeArtifactOperations::class.java)

    internal fun getAuthorizationToken(endpoint: CodeArtifactEndpoint): CodeArtifactToken {
        return codeArtifactClient(endpoint).use { codeArtifact ->
            runBlocking {
                codeArtifact
                    .getAuthorizationToken {
                        domain = endpoint.domain
                        domainOwner = endpoint.domainOwner
                        durationSeconds = 12.hours.inWholeSeconds
                    }
                    .let {
                        CodeArtifactToken(
                            endpoint = endpoint,
                            value = it.authorizationToken!!,
                            expiration = it.expiration?.toJvmInstant()!!,
                        )
                    }
            }
        }
    }

    internal fun publishPackageVersion(
        genericPackage: GenericPackage,
        endpoint: CodeArtifactEndpoint,
    ) {
        codeArtifactClient(endpoint).use { codeArtifact ->
            genericPackage.assets.forEachIndexed { idx: Int, asset ->
                publishArtifact(
                    codeArtifact,
                    endpoint,
                    genericPackage,
                    asset,
                    idx == genericPackage.assets.size - 1,
                )
            }
        }
    }

    private fun publishArtifact(
        codeArtifact: CodeartifactClient,
        endpoint: CodeArtifactEndpoint,
        genericPackage: GenericPackage,
        asset: GenericPackage.Asset,
        finished: Boolean,
    ) {
        runBlocking {
            val (sha256, sha256Time) = measureTimedValue { asset.sha256() }
            logger.lifecycle("Calculated SHA256 for asset '${asset.name}' in $sha256Time; $sha256")

            val timeTaken = measureTime {
                logger.lifecycle(
                    "Uploading CodeArtifact generic artifact asset '${asset.name}' (${genericPackage.namespace}/${genericPackage.name}/${genericPackage.version}) (size: ${asset.content.length()} to ${endpoint.url}"
                )
                // workaround for https://github.com/awslabs/aws-sdk-kotlin/issues/1217
                codeArtifact.publishPackageVersion {
                    domain = endpoint.domain
                    domainOwner = endpoint.domainOwner
                    repository = endpoint.repository
                    namespace = genericPackage.namespace
                    format = PackageFormat.Generic
                    `package` = genericPackage.name
                    packageVersion = genericPackage.version
                    assetSha256 = sha256
                    assetName = asset.name
                    assetContent = asset.content.asByteStream()
                    unfinished = !finished
                }
            }
            logger.lifecycle("Uploaded ${asset.name} in $timeTaken")
        }
    }

    private fun codeArtifactClient(endpoint: CodeArtifactEndpoint): CodeartifactClient {
        return CodeartifactClient {
            region = endpoint.region
            credentialsProvider = buildCredentialsProvider(endpoint.url.queryParameters())
        }
    }

    private fun buildCredentialsProvider(
        queryParameters: Map<String, String>
    ): CredentialsProvider {
        fun mask(value: String?): String? =
            when {
                value == null -> null
                value.length > 4 -> value.take(4) + "*".repeat(value.length - 4)
                else -> value
            }

        val systemProperties =
            System.getProperties()
                .stringPropertyNames()
                .filter {
                    it.lowercase().contains("codeartifact") || it.lowercase().contains("aws")
                }
                .sorted()
                .associate { it to mask(System.getProperty(it)) }
        val envVars =
            System.getenv()
                .keys
                .filter {
                    it.lowercase().contains("codeartifact") || it.lowercase().contains("aws")
                }
                .sorted()
                .associate { it to mask(System.getenv(it)) }

        logger.info("CodeArtifact System properties: {}", systemProperties)
        logger.info("CodeArtifact Environment variables: {}", envVars)

        val profileKey = "codeartifact.profile"

        val providers =
            listOfNotNull(
                (queryParameters[profileKey] ?: resolveSystemVar(profileKey))?.let {
                    logger.info("Using profile {} for CodeArtifact authentication", it)
                    ProfileCredentialsProvider(profileName = it)
                },
                CodeArtifactEnvironmentCredentialsProvider(),

                // https://docs.aws.amazon.com/sdk-for-kotlin/latest/developer-guide/credential-providers.html
                DefaultChainCredentialsProvider(),
            )

        val bootstrapProviders = CredentialsProviderChain(providers)
        val stsRoleArnKey = "codeartifact.stsRoleArn"
        val assumeRoleArn = resolveSystemVar(stsRoleArnKey)
        logger.info("Assume role arn to get CodeArtifact token: {}", mask(assumeRoleArn))
        val provider =
            assumeRoleArn?.let { roleArn ->
                StsAssumeRoleCredentialsProvider(
                    bootstrapCredentialsProvider = bootstrapProviders,
                    assumeRoleParameters =
                        AssumeRoleParameters(
                            roleArn = roleArn,
                            roleSessionName = "codeartifact-client",

                            // scope down the policy so this client can *only* do CodeArtifact
                            // actions, regardless of
                            // what the underlying policy allows
                            policy =
                                """
                        {
                          "Version": "2012-10-17",
                          "Statement": [
                            {
                              "Effect": "Allow",
                              "Action": "codeartifact:*",
                              "Resource": "*"
                            },
                            {
                              "Effect": "Allow",
                              "Action": "sts:GetServiceBearerToken",
                              "Resource": "*"                           
                            }
                          ]
                        }
                    """
                                    .trimIndent(),
                        ),
                )
            } ?: bootstrapProviders

        return CachedCredentialsProvider(provider)
    }

    private class CodeArtifactEnvironmentCredentialsProvider : CredentialsProvider {
        private fun requireEnv(variable: String): String =
            resolveSystemVar(variable)
                ?: throw ProviderConfigurationException(
                    "Missing value for environment variable `$variable`"
                )

        override suspend fun resolve(attributes: Attributes): Credentials {
            return Credentials(
                accessKeyId = requireEnv("codeartifact.accessKeyId"),
                secretAccessKey = requireEnv("codeartifact.secretAccessKey"),
                sessionToken = resolveSystemVar("codeartifact.sessionToken"),
            )
        }
    }
}
