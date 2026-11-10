package io.cloudshiftdev.gradle.codeartifact.internal.gradle

import java.net.URI
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.invocation.Gradle
import org.gradle.internal.resource.connector.ResourceConnectorFactory

/**
 * Seam over the Gradle internals this plugin depends on. Consumers call these interfaces; the
 * version-specific (reflective) details live in the implementations, selected once by
 * [DefaultGradleInternals].
 */
internal interface GradleInternals {
    fun connectorRegistry(gradle: Gradle): ResourceConnectorRegistry

    val httpErrors: HttpErrorFactory

    /**
     * Sets [credentials] on [repository] without resolving them, so a provider-backed password
     * (e.g. a CodeArtifact token) is only fetched when Gradle actually uses it.
     *
     * @return `false` if this Gradle version offers no way to do that; callers then fall back to
     *   the public (eager) credentials API.
     */
    fun setLazyCredentials(
        repository: MavenArtifactRepository,
        credentials: PasswordCredentials,
    ): Boolean
}

/** Registers [ResourceConnectorFactory] instances with Gradle's repository transport. */
internal interface ResourceConnectorRegistry {
    /** Registers the factory produced by [factory] unless [protocol] is already supported. */
    fun registerIfAbsent(protocol: String, factory: () -> ResourceConnectorFactory)
}

/**
 * Creates the exception Gradle expects for a non-success HTTP status, so that Gradle's retry and
 * repository-disabling logic (e.g. retry on 5xx/408/429) applies to our transport.
 */
internal fun interface HttpErrorFactory {
    fun create(method: String, uri: URI, statusCode: Int, reason: String?): RuntimeException
}
