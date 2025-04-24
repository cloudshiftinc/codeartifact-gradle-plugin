package io.cloudshiftdev.gradle.codeartifact

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import java.time.Instant

internal fun codeArtifactEndpoint(scheme: String = "https"): CodeArtifactEndpoint {
    return "$scheme://test-domain-123456789012.d.codeartifact.eu-west-1.amazonaws.com/maven/env-data"
        .toCodeArtifactEndpoint()
}

internal fun codeArtifactToken(
    endpoint: CodeArtifactEndpoint = codeArtifactEndpoint(),
    expired: Boolean = false,
): CodeArtifactToken {
    return CodeArtifactToken(
        endpoint = endpoint,
        value = "abcdef",
        expiration =
            if (expired) Instant.now().minusSeconds(3600) else Instant.now().plusSeconds(3600),
    )
}
