package io.cloudshiftdev.gradle.codeartifact.codeartifact

import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProfileCredentialsProvider
import aws.sdk.kotlin.runtime.auth.credentials.ProviderConfigurationException
import aws.sdk.kotlin.services.codeartifact.CodeartifactClient
import aws.sdk.kotlin.services.codeartifact.getAuthorizationToken
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProviderChain
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.time.toJvmInstant
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

internal abstract class CodeArtifactTokenValueSource :
    ValueSource<String, CodeArtifactTokenValueSource.Parameters> {
    private val logger = Logging.getLogger(CodeArtifactTokenValueSource::class.java)

    private val localCache = LocalCache()

    interface Parameters : ValueSourceParameters {
        val endpoint: Property<CodeArtifactEndpoint>
    }

    override fun obtain(): String {
        return tokenForEndpoint(parameters.endpoint.get())
    }

    private fun tokenForEndpoint(endpoint: CodeArtifactEndpoint): String {
        logger.info("Looking in cache for CodeArtifact token for {}", endpoint.url)
        return localCache
            .load(endpoint) {
                logger.lifecycle("Fetching CodeArtifact token for {}", endpoint.url)

                val queryParameters = endpoint.url.queryParameters()
                val codeArtifact = CodeartifactClient {
                    this.region = endpoint.region
                    this.credentialsProvider = buildCredentialsProvider(queryParameters)
                }

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
                                expiration = it.expiration?.toJvmInstant()!!
                            )
                        }
                }
            }
            .value
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
                DefaultChainCredentialsProvider()
            )

        return CredentialsProviderChain(providers)
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
                sessionToken = resolveSystemVar("codeartifact.sessionToken")
            )
        }
    }
}
