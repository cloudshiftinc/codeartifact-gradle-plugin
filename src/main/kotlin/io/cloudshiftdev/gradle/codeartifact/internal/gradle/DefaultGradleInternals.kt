package io.cloudshiftdev.gradle.codeartifact.internal.gradle

import java.lang.reflect.InaccessibleObjectException
import java.lang.reflect.InvocationTargetException
import org.gradle.api.GradleException
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging
import org.gradle.util.GradleVersion

/** Selects the implementation of each seam that matches the running Gradle version. */
internal class DefaultGradleInternals(
    private val classLoader: ClassLoader = DefaultGradleInternals::class.java.classLoader
) : GradleInternals {
    private val logger = Logging.getLogger(DefaultGradleInternals::class.java)

    private val connectorRegistryFactory: (Gradle) -> ResourceConnectorRegistry by lazy {
        guarded("locate RepositoryTransportFactory") {
            ReflectiveResourceConnectorRegistry.factory(classLoader)
        }
    }

    override fun connectorRegistry(gradle: Gradle): ResourceConnectorRegistry =
        guarded("obtain RepositoryTransportFactory") { connectorRegistryFactory(gradle) }

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

    override fun setLazyCredentials(
        repository: MavenArtifactRepository,
        credentials: PasswordCredentials,
    ): Boolean = ReflectiveRepositoryCredentials.set(repository, credentials)

    companion object {
        val instance: GradleInternals by lazy { DefaultGradleInternals() }
    }
}

/**
 * Runs [block], turning failures caused by Gradle's internals not matching what this plugin expects
 * into a single, actionable error naming the running Gradle version.
 */
internal fun <T> guarded(what: String, block: () -> T): T =
    try {
        block()
    } catch (e: InvocationTargetException) {
        throw UnsupportedGradleInternalsException(what, e.targetException)
    } catch (e: UnsupportedGradleInternalsException) {
        throw e
    } catch (e: LinkageError) {
        throw UnsupportedGradleInternalsException(what, e)
    } catch (e: ReflectiveOperationException) {
        throw UnsupportedGradleInternalsException(what, e)
    } catch (e: ClassCastException) {
        throw UnsupportedGradleInternalsException(what, e)
    } catch (e: UnsupportedOperationException) {
        throw UnsupportedGradleInternalsException(what, e)
    } catch (e: IllegalArgumentException) {
        // Method.invoke / Field.get on a receiver of an unexpected type
        throw UnsupportedGradleInternalsException(what, e)
    } catch (e: InaccessibleObjectException) {
        throw UnsupportedGradleInternalsException(what, e)
    }

internal class UnsupportedGradleInternalsException(what: String, cause: Throwable?) :
    GradleException(
        "The CodeArtifact plugin is not compatible with Gradle ${GradleVersion.current().version}: " +
            "unable to $what (${cause?.toString() ?: "unexpected Gradle internals"}). " +
            "Check for a newer plugin release; see " +
            "https://github.com/cloudshiftinc/codeartifact-gradle-plugin#compatibility",
        cause,
    )
