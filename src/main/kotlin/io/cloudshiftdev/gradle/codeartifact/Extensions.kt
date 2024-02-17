package io.cloudshiftdev.gradle.codeartifact

import java.net.URI
import java.security.MessageDigest
import net.pearx.kasechange.toScreamingSnakeCase

internal fun URI.queryParameters() =
    query?.split("&")?.associate {
        val (key, value) = it.split("=", limit = 2)
        key to value
    } ?: emptyMap()

internal fun resolveSystemVar(key: String): String? = SystemVariableResolver.of().resolve(key)

@OptIn(ExperimentalStdlibApi::class)
internal fun String.sha256(): String {
    return MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).toHexString()
}

private fun interface SystemVariableResolver {
    fun resolve(key: String): String?

    companion object {
        fun of(): SystemVariableResolver =
            composite(systemProperty(), systemEnvironment())

        private fun systemProperty() =
            SystemVariableResolver { key -> System.getProperty(key) }

        private fun systemEnvironment() =
            SystemVariableResolver { key -> System.getenv(key.toScreamingSnakeCase()) }

        private fun composite(vararg resolvers: SystemVariableResolver) =
            SystemVariableResolver { key ->
                resolvers.firstNotNullOfOrNull {
                    it.resolve(key)?.takeIf(String::isNotBlank)
                }
            }
    }
}
