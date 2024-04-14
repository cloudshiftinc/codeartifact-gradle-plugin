package io.cloudshiftdev.gradle.codeartifact

import com.fasterxml.jackson.annotation.JsonIgnore
import java.io.Serializable
import java.net.URI

internal data class CodeArtifactEndpoint(
    val domain: String,
    val domainOwner: String,
    val region: String,
    val repository: String,
    val url: URI
) : Serializable {
    @get:JsonIgnore
    val cacheKey: String
        get() = url.toString()

    companion object {
        private fun fromUrl(url: String): CodeArtifactEndpoint? {
            return fromUrl(URI(url))
        }

        private fun fromUrl(url: URI): CodeArtifactEndpoint? {
            val urlString = url.toString()
            val match = regex.matchEntire(urlString) ?: return null
            return CodeArtifactEndpoint(
                match.groups["domain"]!!.value,
                match.groups["domainOwner"]!!.value,
                match.groups["region"]!!.value,
                match.groups["repository"]!!.value,
                URI(urlString)
            )
        }

        fun URI.toCodeArtifactEndpointOrNull(): CodeArtifactEndpoint? = fromUrl(this)

        fun URI.toCodeArtifactEndpoint(): CodeArtifactEndpoint =
            toCodeArtifactEndpointOrNull() ?: error("Invalid CodeArtifact endpoint: $this")

        fun String.toCodeArtifactEndpoint(): CodeArtifactEndpoint =
            toCodeArtifactEndpointOrNull() ?: error("Invalid CodeArtifact endpoint: $this")

        fun String.toCodeArtifactEndpointOrNull(): CodeArtifactEndpoint? = fromUrl(this)

        private val regex =
            """^https://(?<domain>.*?)-(?<domainOwner>.*?).d.codeartifact.(?<region>.+?).amazonaws.com/.+?/(?<repository>.+?)(?:/|\?.*|/\?.*)?$"""
                .toRegex()
    }
}
