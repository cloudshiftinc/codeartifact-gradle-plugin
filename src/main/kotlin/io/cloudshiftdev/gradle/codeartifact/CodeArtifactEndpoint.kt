package io.cloudshiftdev.gradle.codeartifact

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import java.io.Serializable
import java.net.URI
import net.pearx.kasechange.toPascalCase

@JsonDeserialize(`as` = CodeArtifactEndpointImpl::class)
public interface CodeArtifactEndpoint {
    public val domain: String
    public val domainOwner: String
    public val region: String
    public val repository: String
    public val type: String
    public val url: URI
    public val name: String
        get() = "${domain}-${repository}".toPascalCase()

    public companion object {
        private fun fromUrl(url: String): CodeArtifactEndpoint? {
            return fromUrl(URI(url))
        }

        private fun fromUrl(url: URI): CodeArtifactEndpoint? {
            val urlString = url.toString().replace("codeartifact://", "https://")
            val match = regex.matchEntire(urlString) ?: return null
            return CodeArtifactEndpointImpl(
                domain = match.groups["domain"]!!.value,
                domainOwner = match.groups["domainOwner"]!!.value,
                region = match.groups["region"]!!.value,
                repository = match.groups["repository"]!!.value,
                type = match.groups["type"]!!.value,
                url = URI(urlString),
            )
        }

        public fun URI.toCodeArtifactEndpointOrNull(): CodeArtifactEndpoint? = fromUrl(this)

        public fun URI.toCodeArtifactEndpoint(): CodeArtifactEndpoint =
            toCodeArtifactEndpointOrNull() ?: error("Invalid CodeArtifact endpoint: $this")

        public fun String.toCodeArtifactEndpoint(): CodeArtifactEndpoint =
            toCodeArtifactEndpointOrNull() ?: error("Invalid CodeArtifact endpoint: $this")

        public fun String.toCodeArtifactEndpointOrNull(): CodeArtifactEndpoint? = fromUrl(this)

        // https://env-production-123456789012.d.codeartifact.eu-west-1.amazonaws.com/maven/env-data/com/abcd/xyz-sdk/1.22.3/xyz-sdk-1.22.3.pom
        private val regex =
            """^https://(?<domain>.*?)-(?<domainOwner>[0-9].*?).d.codeartifact.(?<region>.+?).amazonaws.com(?::[0-9]+)?/(?<type>.+?)/(?<repository>.+?)(?:/|\?.*|/\?.*)?$"""
                .toRegex()
    }
}

internal fun CodeArtifactEndpoint.toCodeArtifactProtocolUrl(): URI =
    URI(url.toString().replace("https://", "codeartifact://"))

internal fun URI.toHttpsProtocolUrl(): URI = URI(toString().replace("codeartifact://", "https://"))

internal val CodeArtifactEndpoint.cacheKey
    get() = "${domain}-${domainOwner}-${region}"

internal data class CodeArtifactEndpointImpl(
    override val domain: String,
    override val domainOwner: String,
    override val region: String,
    override val repository: String,
    override val url: URI,
    override val type: String,
) : Serializable, CodeArtifactEndpoint {

    @get:JsonIgnore
    override val name: String
        get() = super.name
}
