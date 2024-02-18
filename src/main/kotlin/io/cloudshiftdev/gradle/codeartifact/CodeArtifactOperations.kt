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
import aws.sdk.kotlin.services.codeartifact.withConfig
import aws.smithy.kotlin.runtime.auth.awscredentials.CachedCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProviderChain
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.content.asByteStream
import aws.smithy.kotlin.runtime.time.toJvmInstant
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import kotlin.time.Duration.Companion.hours
import kotlin.time.measureTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import org.gradle.api.logging.Logging

public object CodeArtifactOperations {
    private val logger = Logging.getLogger(CodeArtifactOperations::class.java)

    public fun getAuthorizationToken(codeArtifactRepositoryUrl: String): String {
        return getAuthorizationToken(codeArtifactRepositoryUrl.toCodeArtifactEndpoint()).value
    }

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
        endpoint: CodeArtifactEndpoint
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

    public fun publishPackageVersion(
        genericPackage: GenericPackage,
        codeArtifactRepositoryUrl: String
    ) {
        publishPackageVersion(genericPackage, codeArtifactRepositoryUrl.toCodeArtifactEndpoint())
    }

    private fun publishArtifact(
        codeArtifact: CodeartifactClient,
        endpoint: CodeArtifactEndpoint,
        genericPackage: GenericPackage,
        asset: GenericPackage.Asset,
        finished: Boolean
    ) {
        runBlocking {
            val (sha256, sha256Time) = measureTimedValue { asset.sha256() }
            logger.lifecycle(
                "Calculated SHA256 for asset '${asset.name}' in $sha256Time; $sha256",
            )

            val timeTaken = measureTime {
                logger.lifecycle(
                    "Uploading CodeArtifact generic artifact asset '${asset.name}' (${genericPackage.namespace}/${genericPackage.name}/${genericPackage.version}) (size: ${asset.content.length()} to ${endpoint.url}",
                )
                // workaround for https://github.com/awslabs/aws-sdk-kotlin/issues/1217
                codeArtifact
                    .withConfig { interceptors += PrecomputedHashInterceptor(sha256) }
                    .use {
                        it.publishPackageVersion {
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
        val profileKey = "codeartifact.profile"

        val providers =
            listOfNotNull(
                (queryParameters[profileKey] ?: resolveSystemVar(profileKey))?.let {
                    ProfileCredentialsProvider(profileName = it)
                },
                CodeArtifactEnvironmentCredentialsProvider(),

                // https://docs.aws.amazon.com/sdk-for-kotlin/latest/developer-guide/credential-providers.html
                DefaultChainCredentialsProvider(),
            )

        val bootstrapProviders = CredentialsProviderChain(providers)
        val stsRoleArnKey = "codeartifact.stsRoleArn"

        val provider =
            resolveSystemVar(stsRoleArnKey)?.let { roleArn ->
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
                    "Missing value for environment variable `$variable`",
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
