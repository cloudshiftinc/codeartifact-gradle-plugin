package io.cloudshiftdev.gradle.codeartifact.codeartifact

import java.net.URI
import net.pearx.kasechange.toScreamingSnakeCase

internal fun URI.queryParameters() =
    query?.split("&")?.associate {
        val (key, value) = it.split("=", limit = 2)
        key to value
    } ?: emptyMap()

internal fun resolveSystemVar(key: String): String? {
    return System.getProperty(key)?.takeIf(String::isNotBlank)
        ?: System.getenv(key.toScreamingSnakeCase())?.takeIf(String::isNotBlank)
}
