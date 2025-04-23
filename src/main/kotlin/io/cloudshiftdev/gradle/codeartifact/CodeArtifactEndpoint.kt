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
            val urlString = url.toString()
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
            """^(?:https|codeartifact)://(?<domain>.*?)-(?<domainOwner>[0-9].*?).d.codeartifact.(?<region>.+?).amazonaws.com/(?<type>.+?)/(?<repository>.+?)(?:/|\?.*|/\?.*)?$"""
                .toRegex()
    }
}

internal fun CodeArtifactEndpoint.proxyUrl(): URI? {
    val proxyEnabled = resolveSystemVar("codeartifact.proxy.enabled")?.toBoolean() ?: true
    if (!proxyEnabled) return null

    val key1 = "codeartifact.${domain}-${domainOwner}-${region}.proxy.base-url"
    val key2 = "codeartifact.${region}.proxy.base-url"
    val key3 = "codeartifact.proxy.base-url"
    return resolveSystemVar(key1, key2, key3)?.let { proxyBaseUrl -> URI(proxyBaseUrl) }
}

internal val CodeArtifactEndpoint.isCodeArtifactProtocol: Boolean
    get() = url.scheme == "codeartifact"

internal fun URI.httpsProtocolUrl(): URI =
    if (scheme == "https") this else URI(toString().replace("codeartifact://", "https://"))

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
