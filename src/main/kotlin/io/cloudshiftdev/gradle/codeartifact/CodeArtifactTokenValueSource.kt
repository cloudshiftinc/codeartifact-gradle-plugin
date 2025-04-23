package io.cloudshiftdev.gradle.codeartifact

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

private val logger = Logging.getLogger(CodeArtifactTokenValueSource::class.java)

internal abstract class CodeArtifactTokenValueSource :
    ValueSource<String, CodeArtifactTokenValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val endpoint: Property<CodeArtifactEndpoint>
    }

    override fun obtain(): String = parameters.endpoint.get().acquireToken()
}

private val localCache =
    LocalCache(File(System.getProperty("user.home")).resolve(".gradle/caches/codeartifact"))
private val memoryCache = MemoryCache()

internal fun CodeArtifactEndpoint.acquireToken(): String {
    val endpoint = this
    try {
        return memoryCache
            .get(endpoint) {
                logger.info(
                    "Looking in local cache for CodeArtifact token for ${endpoint.cacheKey}"
                )
                localCache.load(endpoint) { CodeArtifactOperations.getAuthorizationToken(endpoint) }
            }
            .value
    } catch (e: Exception) {
        val rootCause = e.rootCause
        println(
            "ERROR: failed to obtain CodeArtifact token for ${endpoint.cacheKey}: ${rootCause.message}"
        )
        throw GradleException(
            "Failed to obtain CodeArtifact token for ${endpoint.cacheKey}: ${rootCause.message}",
            e,
        )
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

private class MemoryCache {
    private val cache = ConcurrentHashMap<String, CodeArtifactToken>()

    fun get(endpoint: CodeArtifactEndpoint, provider: () -> CodeArtifactToken): CodeArtifactToken =
        cache[endpoint.cacheKey]?.takeIf { !it.expired }
            ?: provider().also { token -> cache[endpoint.cacheKey] = token }
}
