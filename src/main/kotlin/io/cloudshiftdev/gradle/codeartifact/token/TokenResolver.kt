package io.cloudshiftdev.gradle.codeartifact.token

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactToken
import io.cloudshiftdev.gradle.codeartifact.cacheKey
import io.cloudshiftdev.gradle.codeartifact.service.CodeArtifactService
import io.cloudshiftdev.gradle.codeartifact.service.DefaultCodeArtifactService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.GradleException

internal class DefaultTokenResolver(private val codeArtifactService: CodeArtifactService) :
    TokenResolver {
    private val resolver =
        ErrorMessageTokenResolver(
            MemoryCacheTokenResolver(
                PersistentCacheTokenResolver(
                    cacheDir =
                        File(System.getProperty("user.home"))
                            .resolve(".gradle/caches/codeartifact"),
                    delegate = { codeArtifactService.getAuthorizationToken(it) },
                )
            )
        )

    override fun resolve(endpoint: CodeArtifactEndpoint): CodeArtifactToken {
        return resolver.resolve(endpoint)
    }

    companion object {
        /** Process-wide instance so the in-memory token cache survives across daemon builds. */
        val shared: DefaultTokenResolver by lazy {
            DefaultTokenResolver(DefaultCodeArtifactService.shared)
        }
    }
}

internal fun interface TokenResolver {
    fun resolve(endpoint: CodeArtifactEndpoint): CodeArtifactToken
}

internal class ErrorMessageTokenResolver(private val delegate: TokenResolver) : TokenResolver {
    override fun resolve(endpoint: CodeArtifactEndpoint): CodeArtifactToken {
        try {
            return delegate.resolve(endpoint)
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
}

internal class MemoryCacheTokenResolver(private val delegate: TokenResolver) : TokenResolver {
    private val cache = ConcurrentHashMap<String, CodeArtifactToken>()

    override fun resolve(endpoint: CodeArtifactEndpoint): CodeArtifactToken {
        cache[endpoint.cacheKey]
            ?.takeIf { !it.expired }
            ?.let {
                return it
            }
        // compute() locks per key, so concurrent first requests trigger a single fetch
        return cache.compute(endpoint.cacheKey) { _, current ->
            current?.takeIf { !it.expired } ?: delegate.resolve(endpoint)
        }!!
    }
}

internal class PersistentCacheTokenResolver(private val delegate: TokenResolver, cacheDir: File) :
    TokenResolver {
    private val localCache = LocalCache(cacheDir)

    override fun resolve(endpoint: CodeArtifactEndpoint): CodeArtifactToken {
        return localCache.load(endpoint) { delegate.resolve(endpoint) }
    }
}
