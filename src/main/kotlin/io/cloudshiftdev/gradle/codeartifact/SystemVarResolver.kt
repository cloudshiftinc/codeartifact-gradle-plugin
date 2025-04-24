package io.cloudshiftdev.gradle.codeartifact

import net.pearx.kasechange.toScreamingSnakeCase

internal interface SystemVarResolver {
    fun resolve(vararg keys: String): String?
}

internal class DefaultSystemVarResolver : SystemVarResolver {
    override fun resolve(vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { resolveSystemVarInternal(it) }
    }

    private fun resolveSystemVarInternal(key: String): String? =
        System.getProperty(key)?.takeIf(String::isNotBlank)
            ?: System.getenv(key.toScreamingSnakeCase())?.takeIf(String::isNotBlank)
}
