package io.cloudshiftdev.gradle.codeartifact.internal.gradle

import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import org.gradle.api.logging.Logging
import org.gradle.api.resources.ResourceException

/** An [HttpErrorFactory] backed by a reflectively-resolved Gradle exception constructor. */
internal interface ProbedHttpErrorFactory : HttpErrorFactory {
    /** Whether the Gradle class/constructor this factory targets exists at runtime. */
    val available: Boolean
}

internal fun httpErrorMessage(method: String, uri: URI, statusCode: Int, reason: String?): String =
    "Could not $method '$uri'. Received status code $statusCode from server: ${reason.orEmpty()}"

internal abstract class ReflectiveHttpErrorFactory(
    private val className: String,
    private val parameterTypes: Array<Class<*>>,
    private val classLoader: ClassLoader,
) : ProbedHttpErrorFactory {
    private val constructor: Constructor<*>? by lazy {
        try {
            loadClassOrNull(className, classLoader)?.getConstructor(*parameterTypes)
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: LinkageError) {
            // class present but unloadable (e.g. a missing dependency): treat as unavailable
            null
        }
    }

    override val available: Boolean
        get() = constructor != null

    protected fun newInstance(vararg args: Any?): RuntimeException {
        val ctor = constructor ?: error("$className is not available")
        return try {
            ctor.newInstance(*args) as RuntimeException
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}

/**
 * Newer Gradle (present in 9.8 and 9.9, absent in 9.2):
 * `org.gradle.internal.resource.HttpErrorStatusCodeException(URI, String, int, Throwable)`.
 */
internal class Gradle99HttpErrorFactory(classLoader: ClassLoader) :
    ReflectiveHttpErrorFactory(
        "org.gradle.internal.resource.HttpErrorStatusCodeException",
        arrayOf(
            URI::class.java,
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Throwable::class.java,
        ),
        classLoader,
    ) {
    override fun create(method: String, uri: URI, statusCode: Int, reason: String?) =
        newInstance(uri, httpErrorMessage(method, uri, statusCode, reason), statusCode, null)
}

/**
 * Older Gradle (e.g. 9.2):
 * `org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException(String method, String
 * source, int statusCode, String reason)`.
 */
internal class Gradle90HttpErrorFactory(classLoader: ClassLoader) :
    ReflectiveHttpErrorFactory(
        "org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException",
        arrayOf(
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType!!,
            String::class.java,
        ),
        classLoader,
    ) {
    override fun create(method: String, uri: URI, statusCode: Int, reason: String?) =
        newInstance(method, uri.toString(), statusCode, reason.orEmpty())
}

/** Plain (public API) [ResourceException]; Gradle will not treat it as retryable. */
internal object PlainHttpErrorFactory : HttpErrorFactory {
    override fun create(method: String, uri: URI, statusCode: Int, reason: String?) =
        ResourceException(uri, httpErrorMessage(method, uri, statusCode, reason))
}

/**
 * Decorator: if [primary] fails to build its exception (e.g. reflective access breaks), log once
 * and fall back to [fallback] rather than failing with an unrelated error.
 */
internal class FallbackHttpErrorFactory(
    private val primary: HttpErrorFactory,
    private val fallback: HttpErrorFactory = PlainHttpErrorFactory,
) : HttpErrorFactory {
    private val logger = Logging.getLogger(FallbackHttpErrorFactory::class.java)
    private val warned = AtomicBoolean(false)

    override fun create(
        method: String,
        uri: URI,
        statusCode: Int,
        reason: String?,
    ): RuntimeException =
        try {
            primary.create(method, uri, statusCode, reason)
        } catch (e: Exception) {
            if (warned.compareAndSet(false, true)) {
                logger.warn(
                    "Unable to create Gradle's HTTP error exception; CodeArtifact HTTP errors will not be retried",
                    e,
                )
            }
            fallback.create(method, uri, statusCode, reason)
        }
}
