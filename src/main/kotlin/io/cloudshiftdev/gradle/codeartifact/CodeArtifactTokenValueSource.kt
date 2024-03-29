package io.cloudshiftdev.gradle.codeartifact

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
                CodeArtifactOperations.getAuthorizationToken(endpoint)
            }
            .value
    }
}
