package io.cloudshiftdev.gradle.codeartifact

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant
import java.time.temporal.ChronoUnit

internal data class CodeArtifactToken(
    val endpoint: CodeArtifactEndpoint,
    val value: String,
    val expiration: Instant,
) {
    @get:JsonIgnore
    val expired: Boolean
        get() = expiration.minus(20, ChronoUnit.MINUTES) < Instant.now()
}
