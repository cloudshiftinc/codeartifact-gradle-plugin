package io.cloudshiftdev.gradle.codeartifact

import java.net.URI
import java.security.MessageDigest
import net.pearx.kasechange.toScreamingSnakeCase

internal fun URI.queryParameters() =
    query?.split("&")?.associate {
        val (key, value) = it.split("=", limit = 2)
        key to value
    } ?: emptyMap()

internal fun resolveSystemVar(key: String): String? = SystemVariableResolver.of(key).resolve(key)

@OptIn(ExperimentalStdlibApi::class)
internal fun String.sha256(): String {
    return MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).toHexString()
}

private interface SystemVariableResolver {
    fun description(): String
    fun resolve(key: String): String?

    companion object {
        fun of(key: String): SystemVariableResolver = composite(
            systemProperty(key),
            systemEnvironment(key.toScreamingSnakeCase())
        )

        private fun systemProperty(key: String) = object : SystemVariableResolver {
            override fun description() = "System property '$key'"
            override fun resolve(key: String): String? = System.getProperty(key)
        }

        private fun systemEnvironment(key: String) = object : SystemVariableResolver {
            override fun description() = "System environment '$key'"
            override fun resolve(key: String): String? = System.getenv(key)
        }

        private fun composite(vararg resolvers: SystemVariableResolver) =
            object : SystemVariableResolver {
                override fun description() = resolvers.joinToString(", ") { it.description() }
                override fun resolve(key: String): String? {
                    return resolvers.firstNotNullOfOrNull {
                        it.resolve(key)?.takeIf(String::isNotBlank)
                    }
                }
            }
    }

}
