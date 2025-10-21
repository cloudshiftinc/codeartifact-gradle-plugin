package io.cloudshiftdev.gradle.codeartifact.service

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
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.http.engine.crt.CrtHttpEngine
import aws.smithy.kotlin.runtime.time.toJvmInstant
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactToken
import io.cloudshiftdev.gradle.codeartifact.DefaultSystemVarResolver
import io.cloudshiftdev.gradle.codeartifact.GenericPackage
import io.cloudshiftdev.gradle.codeartifact.SystemVarResolver
import io.cloudshiftdev.gradle.codeartifact.cacheKey
import io.cloudshiftdev.gradle.codeartifact.queryParameters
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours
import kotlin.time.measureTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import org.gradle.api.logging.Logging

internal interface CodeArtifactService {
    fun getAuthorizationToken(endpoint: CodeArtifactEndpoint): CodeArtifactToken

    fun publishPackageVersion(genericPackage: GenericPackage, endpoint: CodeArtifactEndpoint)
}

internal class DefaultCodeArtifactService : CodeArtifactService {
    private val logger = Logging.getLogger(DefaultCodeArtifactService::class.java)
    private val httpClient by lazy { CrtHttpEngine() }
    private val clientFactory = CodeArtifactClientFactory({ httpClient })

    override fun getAuthorizationToken(endpoint: CodeArtifactEndpoint): CodeArtifactToken {
        val codeArtifact = clientFactory.create(endpoint)
        return runBlocking {
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

    override fun publishPackageVersion(
        genericPackage: GenericPackage,
        endpoint: CodeArtifactEndpoint,
    ) {
        val codeArtifact = clientFactory.create(endpoint)
        genericPackage.assets.forEachIndexed { idx: Int, asset ->
            publishArtifact(
                codeArtifact,
                endpoint,
                genericPackage,
                asset,
                finished = idx == genericPackage.assets.size - 1,
            )
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
}

private class CodeArtifactClientFactory(private val httpClientFactory: () -> HttpClientEngine) {
    private val logger = Logging.getLogger(CodeArtifactClientFactory::class.java)
    private val systemVarResolver = DefaultSystemVarResolver()

    private val clientCache = ConcurrentHashMap<String, CodeartifactClient>()

    fun create(endpoint: CodeArtifactEndpoint): CodeartifactClient {
        return clientCache.computeIfAbsent(endpoint.cacheKey) {
            CodeartifactClient {
                region = endpoint.region
                credentialsProvider = buildCredentialsProvider(endpoint.url.queryParameters())
                this.httpClient = this@CodeArtifactClientFactory.httpClientFactory()
            }
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
                .associateWith { mask(System.getProperty(it)) }
        val envVars =
            System.getenv()
                .keys
                .filter {
                    it.lowercase().contains("codeartifact") || it.lowercase().contains("aws")
                }
                .sorted()
                .associateWith { mask(System.getenv(it)) }

        logger.debug("CodeArtifact System properties: {}", systemProperties)
        logger.debug("CodeArtifact Environment variables: {}", envVars)

        val profileKey = "codeartifact.profile"

        val providers =
            listOfNotNull(
                (queryParameters[profileKey] ?: systemVarResolver.resolve(profileKey))?.let {
                    logger.info("Using profile {} for CodeArtifact authentication", it)
                    ProfileCredentialsProvider(profileName = it)
                },
                CodeArtifactEnvironmentCredentialsProvider(systemVarResolver),

                // https://docs.aws.amazon.com/sdk-for-kotlin/latest/developer-guide/credential-providers.html
                DefaultChainCredentialsProvider(),
            )

        val bootstrapProviders = CredentialsProviderChain(providers)
        val stsRoleArnKey = "codeartifact.stsRoleArn"
        val assumeRoleArn = systemVarResolver.resolve(stsRoleArnKey)
        logger.info("Assume role arn to get CodeArtifact token: {}", mask(assumeRoleArn))
        val provider =
            assumeRoleArn?.let { roleArn ->
                StsAssumeRoleCredentialsProvider(
                    bootstrapCredentialsProvider = bootstrapProviders,
                    httpClient = this@CodeArtifactClientFactory.httpClientFactory(),
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

    private class CodeArtifactEnvironmentCredentialsProvider(
        private val systemVarResolver: SystemVarResolver
    ) : CredentialsProvider {
        private fun requireEnv(variable: String): String =
            systemVarResolver.resolve(variable)
                ?: throw ProviderConfigurationException(
                    "Missing value for environment variable `$variable`"
                )

        override suspend fun resolve(attributes: Attributes): Credentials {
            return Credentials.Companion(
                accessKeyId = requireEnv("codeartifact.accessKeyId"),
                secretAccessKey = requireEnv("codeartifact.secretAccessKey"),
                sessionToken = systemVarResolver.resolve("codeartifact.sessionToken"),
            )
        }
    }
}
