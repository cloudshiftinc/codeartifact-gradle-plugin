package io.cloudshiftdev.gradle.codeartifact

import org.gradle.api.GradleException
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
        logger.info("Looking in cache for CodeArtifact token for ${endpoint.cacheKey}")
        try {
            return localCache
                .load(endpoint) {
                    logger.lifecycle("Fetching CodeArtifact token for $${endpoint.cacheKey}")
                    CodeArtifactOperations.getAuthorizationToken(endpoint)
                }
                .value
        } catch (e: Exception) {
            val rootCause = e.rootCause
            println(
                "ERROR: failed to obtain CodeArtifact token for ${endpoint.cacheKey}: ${rootCause.message}",
            )
            throw GradleException(
                "Failed to obtain CodeArtifact token for ${endpoint.cacheKey}: ${rootCause.message}",
                e,
            )
        }
    }
}

private val Throwable.rootCause: Throwable
    get() {
        var rootCause = this
        while (rootCause.cause != null) {
            rootCause = rootCause.cause!!
        }
        return rootCause
    }
