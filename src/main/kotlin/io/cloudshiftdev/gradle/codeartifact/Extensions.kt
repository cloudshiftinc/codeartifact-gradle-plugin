package io.cloudshiftdev.gradle.codeartifact

import java.net.URI
import java.security.MessageDigest

internal fun URI.queryParameters() =
    query?.split("&")?.associate {
        val (key, value) = it.split("=", limit = 2)
        key to value
    } ?: emptyMap()

@OptIn(ExperimentalStdlibApi::class)
internal fun String.sha256(): String {
    return MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).toHexString()
}
