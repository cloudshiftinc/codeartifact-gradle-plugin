package io.cloudshiftdev.gradle.codeartifact

import java.net.URI

internal fun interface ProxyResolver {
    fun resolve(endpoint: CodeArtifactEndpoint): URI?
}

internal class DefaultProxyResolver(private val systemVarResolver: DefaultSystemVarResolver) :
    ProxyResolver {
    override fun resolve(endpoint: CodeArtifactEndpoint): URI? {
        val proxyEnabled =
            systemVarResolver.resolve("codeartifact.proxy.enabled")?.toBoolean() ?: true
        if (!proxyEnabled) return null

        val key1 =
            "codeartifact.${endpoint.domain}-${endpoint.domainOwner}-${endpoint.region}.proxy.base-url"
        val key2 = "codeartifact.${endpoint.region}.proxy.base-url"
        val key3 = "codeartifact.proxy.base-url"
        return systemVarResolver.resolve(key1, key2, key3)?.let { proxyBaseUrl ->
            URI(proxyBaseUrl)
        }
    }
}
