package io.cloudshiftdev.gradle.codeartifact

import java.net.URI
import java.security.MessageDigest
import net.pearx.kasechange.toScreamingSnakeCase

internal fun URI.queryParameters() =
    query?.split("&")?.associate {
        val (key, value) = it.split("=", limit = 2)
        key to value
    } ?: emptyMap()

internal fun resolveSystemVar(key: String): String? =
    System.getProperty(key)?.takeIf(String::isNotBlank)
        ?: System.getenv(key.toScreamingSnakeCase())?.takeIf(String::isNotBlank)

@OptIn(ExperimentalStdlibApi::class)
internal fun String.sha256(): String {
    return MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).toHexString()
}
