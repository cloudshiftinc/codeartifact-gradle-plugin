package io.cloudshiftdev.gradle.codeartifact

import io.cloudshiftdev.gradle.codeartifact.service.registerCodeArtifactBuildService
import io.cloudshiftdev.gradle.codeartifact.token.registerCodeArtifactTokenService
import java.net.URI
import java.security.MessageDigest
import org.gradle.api.Project
import org.gradle.api.provider.Provider

internal fun URI.queryParameters() =
    query?.split("&")?.associate {
        val (key, value) = it.split("=", limit = 2)
        key to value
    } ?: emptyMap()

@OptIn(ExperimentalStdlibApi::class)
internal fun String.sha256(): String {
    return MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).toHexString()
}

public fun Project.codeArtifactToken(endpoint: CodeArtifactEndpoint): Provider<String> {
    val codeArtifactService = project.gradle.registerCodeArtifactBuildService()
    return project.gradle.registerCodeArtifactTokenService(codeArtifactService).map { service ->
        service.resolve(endpoint).value
    }
}
