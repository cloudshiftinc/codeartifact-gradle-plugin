package io.cloudshiftdev.gradle.codeartifact

import java.io.Serializable
import java.net.URI
import net.pearx.kasechange.toPascalCase

public interface CodeArtifactEndpoint {
    public val domain: String
    public val domainOwner: String
    public val region: String
    public val repository: String
    public val url: URI
    public val name: String
        get() = "${domain}-${repository}".toPascalCase()

    public companion object {
        private fun fromUrl(url: String): CodeArtifactEndpoint? {
            return fromUrl(URI(url))
        }

        private fun fromUrl(url: URI): CodeArtifactEndpoint? {
            val urlString = url.toString()
            val match = regex.matchEntire(urlString) ?: return null
            return CodeArtifactEndpointImpl(
                match.groups["domain"]!!.value,
                match.groups["domainOwner"]!!.value,
                match.groups["region"]!!.value,
                match.groups["repository"]!!.value,
                URI(urlString)
            )
        }

        public fun URI.toCodeArtifactEndpointOrNull(): CodeArtifactEndpoint? = fromUrl(this)

        public fun URI.toCodeArtifactEndpoint(): CodeArtifactEndpoint =
            toCodeArtifactEndpointOrNull() ?: error("Invalid CodeArtifact endpoint: $this")

        public fun String.toCodeArtifactEndpoint(): CodeArtifactEndpoint =
            toCodeArtifactEndpointOrNull() ?: error("Invalid CodeArtifact endpoint: $this")

        public fun String.toCodeArtifactEndpointOrNull(): CodeArtifactEndpoint? = fromUrl(this)

        private val regex =
            """^https://(?<domain>.*?)-(?<domainOwner>.*?).d.codeartifact.(?<region>.+?).amazonaws.com/.+?/(?<repository>.+?)(?:/|\?.*|/\?.*)?$"""
                .toRegex()
    }
}

internal val CodeArtifactEndpoint.cacheKey: String
    get() = url.toString()

internal data class CodeArtifactEndpointImpl(
    override val domain: String,
    override val domainOwner: String,
    override val region: String,
    override val repository: String,
    override val url: URI
) : Serializable, CodeArtifactEndpoint
