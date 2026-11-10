package io.cloudshiftdev.gradle.codeartifact.internal.gradle

import org.gradle.api.internal.GradleInternal
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging

/** Selects the implementation of each seam that matches the running Gradle version. */
internal class DefaultGradleInternals(
    private val classLoader: ClassLoader = DefaultGradleInternals::class.java.classLoader
) : GradleInternals {
    private val logger = Logging.getLogger(DefaultGradleInternals::class.java)

    private val transportFactoryClass: Class<*> by lazy {
        ReflectiveResourceConnectorRegistry.resolveTransportFactoryClass(classLoader)
    }

    override fun connectorRegistry(gradle: Gradle): ResourceConnectorRegistry =
        ReflectiveResourceConnectorRegistry(
            transportFactoryClass,
            (gradle as GradleInternal).services.get(transportFactoryClass),
        )

    override val httpErrors: HttpErrorFactory by lazy {
        val candidates =
            listOf(Gradle99HttpErrorFactory(classLoader), Gradle90HttpErrorFactory(classLoader))
        val selected = candidates.firstOrNull { it.available }
        if (selected == null) {
            // Don't fail the build over this: plain errors still work, just without Gradle's retry.
            logger.warn(
                "Gradle's HttpErrorStatusCodeException not found; HTTP errors from CodeArtifact will not be retried"
            )
            PlainHttpErrorFactory
        } else {
            FallbackHttpErrorFactory(selected)
        }
    }

    companion object {
        val instance: GradleInternals by lazy { DefaultGradleInternals() }
    }
}
