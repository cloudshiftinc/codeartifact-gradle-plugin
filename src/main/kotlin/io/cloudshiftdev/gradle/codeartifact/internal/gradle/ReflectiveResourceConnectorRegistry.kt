package io.cloudshiftdev.gradle.codeartifact.internal.gradle

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.gradle.api.invocation.Gradle
import org.gradle.internal.resource.connector.ResourceConnectorFactory

/**
 * Adds connector factories to Gradle's internal `RepositoryTransportFactory` by appending to its
 * private `registeredProtocols` list.
 */
internal class ReflectiveResourceConnectorRegistry(
    private val connectorFactories: MutableList<ResourceConnectorFactory>
) : ResourceConnectorRegistry {

    override fun registerIfAbsent(protocol: String, factory: () -> ResourceConnectorFactory) {
        synchronized(connectorFactories) {
            if (connectorFactories.none { it.supportedProtocols.contains(protocol) }) {
                val connectorFactory = factory()
                guarded("register the '$protocol' transport") {
                    connectorFactories.add(connectorFactory)
                }
            }
        }
    }

    companion object {
        /**
         * Gradle's internal `RepositoryTransportFactory`, resolved by name because Gradle 9.9 moved
         * it to a different package.
         */
        private val transportFactoryClassNames =
            listOf(
                // Gradle 9.9+
                "org.gradle.internal.resource.transport.RepositoryTransportFactory",
                "org.gradle.api.internal.artifacts.repositories.transport.RepositoryTransportFactory",
            )

        fun resolveTransportFactoryClass(classLoader: ClassLoader): Class<*> =
            transportFactoryClassNames.firstNotNullOfOrNull { loadClassOrNull(it, classLoader) }
                ?: throw UnsupportedGradleInternalsException(
                    "locate RepositoryTransportFactory",
                    ClassNotFoundException(transportFactoryClassNames.joinToString()),
                )

        /**
         * Resolves (and validates) everything needed once; the returned function only does the
         * per-build service lookup.
         */
        fun factory(classLoader: ClassLoader): (Gradle) -> ResourceConnectorRegistry {
            val transportFactoryClass = resolveTransportFactoryClass(classLoader)
            val field = registeredProtocolsField(transportFactoryClass)
            val services = ServiceLookup(classLoader)
            return { gradle ->
                val transportFactory = services.get(gradle, transportFactoryClass)
                @Suppress("UNCHECKED_CAST")
                val list = field.get(transportFactory) as MutableList<ResourceConnectorFactory>
                ReflectiveResourceConnectorRegistry(list)
            }
        }

        private fun registeredProtocolsField(transportFactoryClass: Class<*>): Field {
            val field = transportFactoryClass.getDeclaredField("registeredProtocols")
            if (
                !MutableList::class.java.isAssignableFrom(field.type) ||
                    Modifier.isStatic(field.modifiers)
            ) {
                throw UnsupportedGradleInternalsException(
                    "use RepositoryTransportFactory.registeredProtocols",
                    IllegalStateException("unexpected field: $field"),
                )
            }
            field.isAccessible = true
            return field
        }
    }
}

/**
 * Looks up a build service via `GradleInternal.getServices().get(Class)` reflectively, so that a
 * change to either internal type surfaces as a clear error rather than a linkage error.
 */
private class ServiceLookup(classLoader: ClassLoader) {
    private val getServices: Method =
        Class.forName("org.gradle.api.internal.GradleInternal", false, classLoader)
            .getMethod("getServices")
    private val get: Method = getServices.returnType.getMethod("get", Class::class.java)

    fun get(gradle: Gradle, serviceType: Class<*>): Any =
        get.invoke(getServices.invoke(gradle), serviceType)
            ?: throw UnsupportedGradleInternalsException(
                "obtain ${serviceType.simpleName}",
                IllegalStateException("service not registered"),
            )
}

internal fun loadClassOrNull(name: String, classLoader: ClassLoader): Class<*>? =
    try {
        Class.forName(name, false, classLoader)
    } catch (_: ClassNotFoundException) {
        null
    }
