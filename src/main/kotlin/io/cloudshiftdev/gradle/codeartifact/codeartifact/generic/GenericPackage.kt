package io.cloudshiftdev.gradle.codeartifact.codeartifact.generic

import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

// A generic package consists of a package name, namespace, version, and one or more assets (or
// files)

internal data class GenericPackage(
    val name: String,
    val namespace: String,
    val version: String,
    val assets: List<Asset>
) {

    // https://docs.aws.amazon.com/codeartifact/latest/APIReference/API_PublishPackageVersion.html
    init {
        require(name.length <= 255) { "Package name cannot be longer than 255 characters" }
        require(name.matches(regex)) {
            "Package name can only contain alphanumeric characters, hyphens, underscores, and periods"
        }

        require(namespace.length <= 255) {
            "Package namespace cannot be longer than 255 characters"
        }
        require(namespace.matches(regex)) {
            "Package namespace can only contain alphanumeric characters, hyphens, underscores, and periods"
        }

        require(version.length <= 255) { "Package version cannot be longer than 255 characters" }
        require(version.matches(regex)) {
            "Package version can only contain alphanumeric characters, hyphens, underscores, and periods"
        }

        require(assets.isNotEmpty()) { "Package must have at least one asset" }
    }

    internal data class Asset(val name: String, val content: File) {
        // https://docs.aws.amazon.com/codeartifact/latest/ug/generic-packages-overview.html
        init {
            require(name.isNotBlank()) { "Asset name cannot be blank" }
            require(name.matches(regex)) {
                "Asset name can only contain alphanumeric characters, hyphens, underscores, and periods"
            }

            require(name.trim() == name) { "Asset name cannot have leading or trailing whitespace" }
            require(name.length <= 255) { "Asset name cannot be longer than 255 characters" }
            require(name !in setOf(".", "..")) { "Asset name cannot be '.' or '..'" }
            require(!name.contains("  ")) { "Asset name cannot contain consecutive spaces" }
        }

        @OptIn(ExperimentalStdlibApi::class)
        public fun sha256(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(content.inputStream(), digest).use { it.skip(Long.MAX_VALUE) }
            return digest.digest().toHexString()
        }
    }
}

private val regex = """[^#/\s]+""".toRegex()
