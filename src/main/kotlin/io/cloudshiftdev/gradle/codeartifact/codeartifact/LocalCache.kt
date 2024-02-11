package io.cloudshiftdev.gradle.codeartifact.codeartifact

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.File
import java.security.MessageDigest
import org.gradle.api.logging.Logging

internal class LocalCache {
    private val logger = Logging.getLogger(LocalCache::class.java)

    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    private val cacheDir =
        File(System.getProperty("user.home")).resolve(".gradle/caches/codeartifact")
    private val keysetFile = cacheDir.resolve("codeartifact.keyset.json")

    private val masterKey: KeysetHandle =
        TinkJsonProtoKeysetFormat.parseKeyset(
            """{"primaryKeyId":40967502,"key":[{"keyData":{"typeUrl":"type.googleapis.com/google.crypto.tink.AesGcmKey","value":"GhDTsUr/cPpvS9YhiqngA1ql","keyMaterialType":"SYMMETRIC"},"status":"ENABLED","keyId":40967502,"outputPrefixType":"TINK"}]}""",
            InsecureSecretKeyAccess.get()
        )

    init {
        AeadConfig.register()
    }

    fun get(endpoint: CodeArtifactEndpoint, block: () -> CodeArtifactToken): CodeArtifactToken {
        val cacheFile = cacheFile(endpoint)

        try {
            val decryptedBytes = decrypt(cacheFile.readBytes(), endpoint.cacheKey)
            val token: CodeArtifactToken = mapper.readValue(decryptedBytes)
            if (!token.expired) {
                return token
            }
        } catch (thrown: Exception) {
            logger.lifecycle(
                "Failed to read cached CodeArtifact token {}: {}; re-issuing.",
                cacheFile,
                thrown.message
            )
        }

        cacheFile.delete()
        val token = block()
        store(token)
        return token
    }

    private fun store(token: CodeArtifactToken) {
        val cacheFile = cacheFile(token.endpoint)

        logger.debug("Storing token for {} in cache {}", token.endpoint.url, cacheFile)

        cacheFile.parentFile.mkdirs()
        val json = mapper.writeValueAsString(token)

        encrypt(json.toByteArray(), token.endpoint.cacheKey).let { cacheFile.writeBytes(it) }
    }

    private fun cacheFile(endpoint: CodeArtifactEndpoint): File {
        return cacheDir.resolve("${endpoint.cacheKey.sha256()}.cache")
    }

    private fun decrypt(cipherText: ByteArray, repositoryKey: String): ByteArray {
        val keyset = loadKeyset()
        val aead = keyset.getPrimitive(Aead::class.java)

        return aead.decrypt(cipherText, repositoryKey.toByteArray())
    }

    private fun encrypt(plainText: ByteArray, repositoryKey: String): ByteArray {
        val keyset = loadKeyset()
        val aead = keyset.getPrimitive(Aead::class.java)
        return aead.encrypt(plainText, repositoryKey.toByteArray())
    }

    private fun loadKeyset(): KeysetHandle {
        if (!keysetFile.exists()) {
            generateKeyset()
        }

        return TinkJsonProtoKeysetFormat.parseEncryptedKeyset(
            keysetFile.readText(),
            masterKey.getPrimitive(Aead::class.java),
            emptyAdditionalData
        )
    }

    private fun generateKeyset() {
        val keyset = KeysetHandle.generateNew(PredefinedAeadParameters.AES128_GCM)
        val serializedEncryptedKeyset =
            TinkJsonProtoKeysetFormat.serializeEncryptedKeyset(
                keyset,
                masterKey.getPrimitive(Aead::class.java),
                emptyAdditionalData
            )
        keysetFile.writeText(serializedEncryptedKeyset)
    }

    private val emptyAdditionalData = ByteArray(0)
}

@OptIn(ExperimentalStdlibApi::class)
private fun String.sha256(): String {
    return MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).toHexString()
}
