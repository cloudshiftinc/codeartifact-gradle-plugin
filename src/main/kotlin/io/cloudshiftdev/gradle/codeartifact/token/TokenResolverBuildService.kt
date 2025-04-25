package io.cloudshiftdev.gradle.codeartifact.token

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactToken
import io.cloudshiftdev.gradle.codeartifact.cacheKey
import io.cloudshiftdev.gradle.codeartifact.service.CodeArtifactBuildService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.services.BuildServiceRegistry
import org.gradle.kotlin.dsl.registerIfAbsent

internal interface TokenResolverParameters : BuildServiceParameters {
    val codeArtifactService: Property<CodeArtifactBuildService>
}

internal abstract class TokenResolverBuildService
@Inject
constructor(private val registry: BuildServiceRegistry) :
    BuildService<TokenResolverParameters>, TokenResolver {
    private val resolver =
        ErrorMessageTokenResolver(
            MemoryCacheTokenResolver(
                PersistentCacheTokenResolver(
                    cacheDir =
                        File(System.getProperty("user.home"))
                            .resolve(".gradle/caches/codeartifact"),
                    delegate = { parameters.codeArtifactService.get().getAuthorizationToken(it) },
                )
            )
        )

    override fun resolve(endpoint: CodeArtifactEndpoint): CodeArtifactToken {
        return resolver.resolve(endpoint)
    }
}

internal fun Gradle.registerCodeArtifactTokenService(
    codeArtifactService: Provider<CodeArtifactBuildService>
): Provider<TokenResolverBuildService> {
    return gradle.sharedServices.registerIfAbsent(
        "codeArtifactTokenResolver",
        TokenResolverBuildService::class,
    ) {
        parameters.codeArtifactService.set(codeArtifactService)
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
        return cache[endpoint.cacheKey]?.takeIf { !it.expired }
            ?: delegate.resolve(endpoint).also { cache[endpoint.cacheKey] = it }
    }
}

internal class PersistentCacheTokenResolver(private val delegate: TokenResolver, cacheDir: File) :
    TokenResolver {
    private val localCache = LocalCache(cacheDir)

    override fun resolve(endpoint: CodeArtifactEndpoint): CodeArtifactToken {
        return localCache.load(endpoint) { delegate.resolve(endpoint) }
    }
}
