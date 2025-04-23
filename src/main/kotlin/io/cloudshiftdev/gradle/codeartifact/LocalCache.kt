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
import java.time.Duration
import java.time.Instant
import org.gradle.api.logging.Logging

internal class LocalCache(private val cacheDir: File) {
    private val logger = Logging.getLogger(LocalCache::class.java)
    private val keysetFile = cacheDir.resolve("codeartifact.keyset.json")

    companion object {
        private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

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
            logger.debug("Reading cached CodeArtifact token with key {}", endpoint.cacheKey)
            val decryptedBytes = decrypt(cacheFile.readBytes(), endpoint.cacheKey)
            val token: CodeArtifactToken = mapper.readValue(decryptedBytes)
            if (!token.expired)
                return token.also {
                    logger.info(
                        "Retrieved CodeArtifact token from local cache for key {}",
                        endpoint.cacheKey,
                    )
                }

            logger.info(
                "CodeArtifact token expired/stale. expiration: {}; delta: {}",
                token.expiration,
                Duration.between(Instant.now(), token.expiration),
            )
        } catch (thrown: Exception) {
            logger.info(
                "Failed to read cached CodeArtifact token (removing from cache) {}: {}",
                cacheFile,
                thrown.message,
            )
        }

        cacheFile.delete()
        logger.lifecycle("Fetching CodeArtifact token for ${endpoint.cacheKey}")
        val token = tokenSupplier()
        logger.info(
            "Fetched CodeArtifact token for {}; expires in {}",
            endpoint.cacheKey,
            Duration.between(Instant.now(), token.expiration),
        )
        store(token)
        return token
    }

    private fun store(token: CodeArtifactToken) {
        val cacheFile = cacheFile(token.endpoint)

        logger.debug(
            "Storing CodeArtifact token for {} in cache {}; expiration: {}",
            token.endpoint,
            cacheFile,
            token.expiration,
        )

        cacheFile.parentFile.mkdirs()
        val tokenJson = mapper.writeValueAsString(token)
        logger.debug("CodeArtifact token json: {}", tokenJson)

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
