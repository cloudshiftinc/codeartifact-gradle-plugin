package io.cloudshiftdev.gradle.codeartifact

import aws.sdk.kotlin.services.codeartifact.getAuthorizationToken
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

                runBlocking {
                    codeArtifactClient(endpoint).use { codeArtifact ->
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
            .value
    }
}
