package io.cloudshiftdev.gradle.codeartifact

import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

// A generic package consists of a package name, namespace, version, and one or more assets (or
// files)

internal class GenericPackage(
    val name: String,
    val namespace: String,
    val version: String,
    val assets: List<Asset>,
) {

    // https://docs.aws.amazon.com/codeartifact/latest/APIReference/API_PublishPackageVersion.html
    init {
        require(name.length <= 255) { "Package name cannot be longer than 255 characters" }
        require(name.matches(regex)) {
            "Package name must not be empty and cannot contain whitespace, '#' or '/'"
        }

        require(namespace.length <= 255) {
            "Package namespace cannot be longer than 255 characters"
        }
        require(namespace.matches(regex)) {
            "Package namespace must not be empty and cannot contain whitespace, '#' or '/'"
        }

        require(version.length <= 255) { "Package version cannot be longer than 255 characters" }
        require(version.matches(regex)) {
            "Package version must not be empty and cannot contain whitespace, '#' or '/'"
        }

        require(assets.isNotEmpty()) { "Package must have at least one asset" }
    }

    class Asset(val name: String, val content: File) {
        // https://docs.aws.amazon.com/codeartifact/latest/ug/generic-packages-overview.html
        init {
            // the regex also rules out blank names and leading/trailing/consecutive spaces
            require(name.matches(regex)) {
                "Asset name must not be empty and cannot contain whitespace, '#' or '/'"
            }
            require(name.length <= 255) { "Asset name cannot be longer than 255 characters" }
            require(name !in setOf(".", "..")) { "Asset name cannot be '.' or '..'" }
        }

        @OptIn(ExperimentalStdlibApi::class)
        fun sha256(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            DigestInputStream(content.inputStream(), digest).use {
                while (it.read(buffer) != -1) {}
            }
            return digest.digest().toHexString()
        }
    }
}

private val regex = """[^#/\s]+""".toRegex()
