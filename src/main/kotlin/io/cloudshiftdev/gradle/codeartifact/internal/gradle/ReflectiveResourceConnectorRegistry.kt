package io.cloudshiftdev.gradle.codeartifact.internal.gradle

import org.gradle.internal.resource.connector.ResourceConnectorFactory

/**
 * Adds connector factories to Gradle's internal `RepositoryTransportFactory` by appending to its
 * private `registeredProtocols` list.
 */
internal class ReflectiveResourceConnectorRegistry(
    private val transportFactoryClass: Class<*>,
    private val transportFactory: Any,
) : ResourceConnectorRegistry {

    override fun registerIfAbsent(protocol: String, factory: () -> ResourceConnectorFactory) {
        val connectorFactories = registeredProtocols()
        synchronized(connectorFactories) {
            if (connectorFactories.none { it.supportedProtocols.contains(protocol) }) {
                connectorFactories.add(factory())
            }
        }
    }

    private fun registeredProtocols(): MutableList<ResourceConnectorFactory> {
        val field = transportFactoryClass.getDeclaredField("registeredProtocols")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(transportFactory) as MutableList<ResourceConnectorFactory>
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
                ?: error("Unsupported Gradle version: RepositoryTransportFactory not found")
    }
}

internal fun loadClassOrNull(name: String, classLoader: ClassLoader): Class<*>? =
    try {
        Class.forName(name, false, classLoader)
    } catch (_: ClassNotFoundException) {
        null
    }
