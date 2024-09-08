package io.cloudshiftdev.gradle.codeartifact

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
import org.gradle.api.logging.Logging

internal class LocalCache {
    private val logger = Logging.getLogger(LocalCache::class.java)

    companion object {
        private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

        private val cacheDir =
            File(System.getProperty("user.home")).resolve(".gradle/caches/codeartifact")
        private val keysetFile = cacheDir.resolve("codeartifact.keyset.json")

        private val noAssociatedData = ByteArray(0)

        private val masterKey: KeysetHandle =
            TinkJsonProtoKeysetFormat.parseKeyset(
                """{"primaryKeyId":40967502,"key":[{"keyData":{"typeUrl":"type.googleapis.com/google.crypto.tink.AesGcmKey","value":"GhDTsUr/cPpvS9YhiqngA1ql","keyMaterialType":"SYMMETRIC"},"status":"ENABLED","keyId":40967502,"outputPrefixType":"TINK"}]}""",
                InsecureSecretKeyAccess.get(),
            )

        init {
            AeadConfig.register()
        }
    }

    fun load(
        endpoint: CodeArtifactEndpoint,
        tokenSupplier: () -> CodeArtifactToken,
    ): CodeArtifactToken {
        val cacheFile = cacheFile(endpoint)

        try {
            val decryptedBytes = decrypt(cacheFile.readBytes(), endpoint.cacheKey)
            val token: CodeArtifactToken = mapper.readValue(decryptedBytes)
            if (!token.expired) {
                logger.debug("CodeArtifact token expired {}", token.expiration)
                return token
            }
        } catch (thrown: Exception) {
            logger.debug(
                "Failed to read cached CodeArtifact token {}: {}",
                cacheFile,
                thrown.message,
            )
        }

        cacheFile.delete()
        val token = tokenSupplier()
        store(token)
        return token
    }

    private fun store(token: CodeArtifactToken) {
        val cacheFile = cacheFile(token.endpoint)

        logger.debug("Storing token for {} in cache {}", token.endpoint, cacheFile)

        cacheFile.parentFile.mkdirs()
        val tokenJson = mapper.writeValueAsString(token)

        encrypt(tokenJson, token.endpoint.cacheKey).let { cacheFile.writeBytes(it) }
    }

    private fun cacheFile(endpoint: CodeArtifactEndpoint): File {
        return cacheDir.resolve("${endpoint.cacheKey.sha256()}.cache")
    }

    private fun decrypt(cipherText: ByteArray, repositoryKey: String): ByteArray {
        return loadKeyset().primitive<Aead>().decrypt(cipherText, repositoryKey.toByteArray())
    }

    private fun encrypt(plainText: String, repositoryKey: String): ByteArray {
        return loadKeyset()
            .primitive<Aead>()
            .encrypt(plainText.toByteArray(), repositoryKey.toByteArray())
    }

    private fun loadKeyset(): KeysetHandle {
        if (!keysetFile.exists()) {
            generateKeyset()
        }

        return TinkJsonProtoKeysetFormat.parseEncryptedKeyset(
            keysetFile.readText(),
            masterKey.primitive(),
            noAssociatedData,
        )
    }

    private fun generateKeyset() {
        val keyset = KeysetHandle.generateNew(PredefinedAeadParameters.AES128_GCM)
        val serializedEncryptedKeyset =
            TinkJsonProtoKeysetFormat.serializeEncryptedKeyset(
                keyset,
                masterKey.primitive(),
                noAssociatedData,
            )
        keysetFile.writeText(serializedEncryptedKeyset)
    }
}

private inline fun <reified T : Any> KeysetHandle.primitive(): T {
    return this.getPrimitive(T::class.java)
}
