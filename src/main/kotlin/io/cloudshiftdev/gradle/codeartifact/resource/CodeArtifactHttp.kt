package io.cloudshiftdev.gradle.codeartifact.resource

import io.cloudshiftdev.gradle.codeartifact.SystemVarResolver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.brotli.BrotliInterceptor
import okhttp3.logging.HttpLoggingInterceptor
import org.gradle.api.logging.Logging

/**
 * Builds the OkHttpClient used for `codeartifact://` resolution. Callers hold a single instance for
 * the lifetime of the plugin classloader so warm connections (TCP + TLS) are reused across builds
 * in the same daemon.
 */
internal object CodeArtifactHttp {
    private val logger = Logging.getLogger(CodeArtifactHttp::class.java)

    // Same properties / defaults Gradle's own HTTP transport honours.
    private const val CONNECTION_TIMEOUT_KEY = "org.gradle.internal.http.connectionTimeout"
    private const val SOCKET_TIMEOUT_KEY = "org.gradle.internal.http.socketTimeout"
    private const val DEFAULT_TIMEOUT_MS = 30_000L

    internal const val PROTOCOLS_KEY = "codeartifact.http.protocols"

    private data class Config(
        val connectTimeoutMs: Long,
        val socketTimeoutMs: Long,
        val protocols: List<Protocol>?,
    )

    private val clients = ConcurrentHashMap<Config, OkHttpClient>()

    /** Shared client for the current configuration; reused across builds in the same daemon. */
    fun sharedClient(systemVarResolver: SystemVarResolver): OkHttpClient {
        val config =
            Config(
                connectTimeoutMs = timeoutMs(CONNECTION_TIMEOUT_KEY),
                socketTimeoutMs = timeoutMs(SOCKET_TIMEOUT_KEY),
                protocols = protocols(systemVarResolver),
            )
        return clients.computeIfAbsent(config, ::createClient)
    }

    private fun createClient(config: Config): OkHttpClient {
        val loggingInterceptor =
            HttpLoggingInterceptor(OkHttpLogger()).apply {
                setLevel(HttpLoggingInterceptor.Level.HEADERS)
                redactHeader("Authorization")
            }

        val maxIdleConnections = maxOf(16, Runtime.getRuntime().availableProcessors() * 2)

        return OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(false)
            .connectionPool(ConnectionPool(maxIdleConnections, 5, TimeUnit.MINUTES))
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.socketTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.socketTimeoutMs, TimeUnit.MILLISECONDS)
            .apply { config.protocols?.let { protocols(it) } }
            // Formatting headers for every request is wasted work unless debug logging is on;
            // checked per call as the client outlives a single build's log level.
            .addNetworkInterceptor(
                Interceptor { chain ->
                    if (OkHttpLogger.enabled) loggingInterceptor.intercept(chain)
                    else chain.proceed(chain.request())
                }
            )
            .addInterceptor(BrotliInterceptor)
            .build()
    }

    private fun timeoutMs(key: String): Long =
        System.getProperty(key)?.toLongOrNull()?.takeIf { it >= 0 } ?: DEFAULT_TIMEOUT_MS

    /** Parses e.g. `http/1.1` or `h2,http/1.1`; OkHttp requires http/1.1 to be present. */
    internal fun protocols(systemVarResolver: SystemVarResolver): List<Protocol>? {
        val raw = systemVarResolver.resolve(PROTOCOLS_KEY) ?: return null
        val parsed =
            raw.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapNotNull {
                    try {
                        Protocol.get(it.lowercase())
                    } catch (_: java.io.IOException) {
                        logger.warn("Ignoring unknown protocol '{}' in {}", it, PROTOCOLS_KEY)
                        null
                    }
                }
                .filter { it == Protocol.HTTP_1_1 || it == Protocol.HTTP_2 }
        return (parsed + Protocol.HTTP_1_1).distinct()
    }

    private class OkHttpLogger : HttpLoggingInterceptor.Logger {
        override fun log(message: String) {
            logger.debug(message)
        }

        companion object {
            private val logger = Logging.getLogger(OkHttpLogger::class.java)
            val enabled: Boolean
                get() = logger.isDebugEnabled
        }
    }
}
