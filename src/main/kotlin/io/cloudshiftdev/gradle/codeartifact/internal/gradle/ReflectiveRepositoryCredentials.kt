package io.cloudshiftdev.gradle.codeartifact.internal.gradle

import java.lang.reflect.Method
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.credentials.Credentials
import org.gradle.api.logging.Logging

/**
 * Calls Gradle's internal `AbstractAuthenticationSupportedRepository.setConfiguredCredentials`
 * reflectively. It is the only way to attach credentials whose values stay lazy; the public
 * `credentials(Action)` API copies values eagerly.
 */
internal object ReflectiveRepositoryCredentials {
    private val logger = Logging.getLogger(ReflectiveRepositoryCredentials::class.java)

    private val setters =
        object : ClassValue<Method?>() {
            override fun computeValue(type: Class<*>): Method? =
                try {
                    type.getMethod("setConfiguredCredentials", Credentials::class.java)
                } catch (_: NoSuchMethodException) {
                    null
                }
        }

    fun set(repository: MavenArtifactRepository, credentials: PasswordCredentials): Boolean {
        val setter = setters.get(repository.javaClass) ?: return false
        return try {
            setter.invoke(repository, credentials)
            true
        } catch (e: Exception) {
            // reflective failures (incl. IllegalArgumentException / InaccessibleObjectException):
            // fall back to the public API rather than failing configuration
            logger.debug("Unable to set lazy credentials on {}", repository.name, e)
            false
        }
    }
}
