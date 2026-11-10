package io.cloudshiftdev.gradle.codeartifact.token

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactToken
import io.cloudshiftdev.gradle.codeartifact.cacheKey
import io.cloudshiftdev.gradle.codeartifact.sha256
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
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

        // Per cache file and JVM-wide (shared by every LocalCache instance): parallel dependency
        // resolution would otherwise race on the cache file and fetch duplicate tokens, while
        // other endpoints are not blocked behind a slow token fetch. Keyset creation is safe
        // without a lock (first writer wins).
        private val locks = ConcurrentHashMap<String, Any>()
    }

    fun load(
        endpoint: CodeArtifactEndpoint,
        tokenSupplier: () -> CodeArtifactToken,
    ): CodeArtifactToken {
        val lock = locks.computeIfAbsent(cacheFile(endpoint).absolutePath) { Any() }
        return synchronized(lock) { loadLocked(endpoint, tokenSupplier) }
    }

    private fun loadLocked(
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
        try {
            store(token)
        } catch (thrown: Exception) {
            // the token is valid; failing to cache it (read-only/full disk, bad keyset) must not
            // fail resolution
            logger.warn(
                "Unable to cache CodeArtifact token in {}: {}",
                cacheFile,
                thrown.message,
            )
        }
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

        val tokenJson = mapper.writeValueAsString(token)
        val bytes = encrypt(tokenJson, token.endpoint.cacheKey)
        writeAtomically(cacheFile, bytes, replaceExisting = true)
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

        val keysetText = keysetFile.readText()
        return try {
            parseKeyset(keysetText)
        } catch (thrown: Exception) {
            // an unreadable keyset (corrupt, or from an incompatible version) would otherwise
            // break every build until deleted by hand; tokens cached with it are unreadable anyway
            logger.info(
                "Unreadable CodeArtifact cache keyset {} (regenerating): {}",
                keysetFile,
                thrown.message,
            )
            // only delete the keyset we failed to parse: another thread/process may already have
            // replaced it, and deleting that one would invalidate the tokens cached with it
            if (keysetFile.exists() && keysetFile.readText() == keysetText) keysetFile.delete()
            generateKeyset()
            parseKeyset(keysetFile.readText())
        }
    }

    private fun parseKeyset(keysetText: String): KeysetHandle =
        TinkJsonProtoKeysetFormat.parseEncryptedKeyset(
            keysetText,
            masterKey.primitive(),
            noAssociatedData,
        )

    private fun generateKeyset() {
        val keyset = KeysetHandle.generateNew(PredefinedAeadParameters.AES128_GCM)
        val serializedEncryptedKeyset =
            TinkJsonProtoKeysetFormat.serializeEncryptedKeyset(
                keyset,
                masterKey.primitive(),
                noAssociatedData,
            )
        // first writer wins (also across processes): never replace an existing keyset, as tokens
        // already cached with it would become unreadable.
        writeAtomically(
            keysetFile,
            serializedEncryptedKeyset.toByteArray(),
            replaceExisting = false,
        )
    }

    private fun writeAtomically(target: File, bytes: ByteArray, replaceExisting: Boolean) {
        target.parentFile.mkdirs()
        val tmp = Files.createTempFile(target.parentFile.toPath(), target.name, ".tmp")
        try {
            Files.write(tmp, bytes)
            if (replaceExisting) {
                Files.move(tmp, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } else {
                try {
                    Files.createLink(target.toPath(), tmp)
                } catch (_: FileAlreadyExistsException) {
                    // another writer won
                } catch (_: Exception) {
                    // no hard-link support (UnsupportedOperationException, or an IOException on
                    // e.g. FAT/SMB): a non-replacing move still lets the first writer win
                    try {
                        Files.move(tmp, target.toPath())
                    } catch (_: FileAlreadyExistsException) {
                        // another writer won
                    }
                }
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}

private inline fun <reified T : Any> KeysetHandle.primitive(): T {
    return this.getPrimitive(T::class.java)
}
