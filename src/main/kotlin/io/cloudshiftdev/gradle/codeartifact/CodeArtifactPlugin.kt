package io.cloudshiftdev.gradle.codeartifact

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpointOrNull
import io.cloudshiftdev.gradle.codeartifact.resource.CodeArtifactResourceConnectorFactory
import io.cloudshiftdev.gradle.codeartifact.service.CodeArtifactBuildService
import io.cloudshiftdev.gradle.codeartifact.service.registerCodeArtifactBuildService
import io.cloudshiftdev.gradle.codeartifact.task.PublishPackageVersion
import io.cloudshiftdev.gradle.codeartifact.token.TokenResolverBuildService
import io.cloudshiftdev.gradle.codeartifact.token.registerCodeArtifactTokenService
import javax.inject.Inject
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor
import okhttp3.logging.HttpLoggingInterceptor
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.initialization.Settings
import org.gradle.api.internal.artifacts.repositories.DefaultMavenArtifactRepository
import org.gradle.api.internal.artifacts.repositories.transport.RepositoryTransportFactory
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.PluginAware
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.internal.resource.connector.ResourceConnectorFactory
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.kotlin.dsl.withType

public abstract class CodeArtifactPlugin @Inject constructor(private val objects: ObjectFactory) :
    Plugin<PluginAware> {
    private val logger = Logging.getLogger(CodeArtifactPlugin::class.java)
    private val systemVarResolver = DefaultSystemVarResolver()

    override fun apply(target: PluginAware) {
        when (target) {
            is Project -> applyToProject(target)
            is Settings -> applyToSettings(target)
            is Gradle -> applyToGradle(target)
            else ->
                error("CodeArtifactPlugin is not compatible with ${target.javaClass.simpleName}")
        }
    }

    private fun applyToGradle(gradle: Gradle) {
        val ctx = initPlugin(gradle)

        gradle.beforeSettings {
            buildscript.repositories.all {
                configureForCodeArtifact(RepositoryMode.Resolve, ctx.tokenService)
            }
            pluginManagement.repositories.all {
                configureForCodeArtifact(RepositoryMode.Resolve, ctx.tokenService)
            }
            pluginManager.apply(CodeArtifactPlugin::class)
        }
    }

    private data class PluginContext(
        val tokenService: Provider<TokenResolverBuildService>,
        val codeArtifactService: Provider<CodeArtifactBuildService>,
    )

    private fun initPlugin(gradle: Gradle): PluginContext {
        val codeArtifactService = gradle.registerCodeArtifactBuildService()
        val tokenServiceProvider = gradle.registerCodeArtifactTokenService(codeArtifactService)

        val transportFactory = gradle.serviceOf<RepositoryTransportFactory>()
        transportFactory.addCodeArtifactResourceConnectorFactory(tokenServiceProvider.get())

        return PluginContext(tokenServiceProvider, codeArtifactService)
    }

    private fun applyToSettings(settings: Settings) {
        val ctx = initPlugin(settings.gradle)
        settings.pluginManagement.repositories.all {
            configureForCodeArtifact(RepositoryMode.Resolve, ctx.tokenService)
        }

        settings.dependencyResolutionManagement.repositories.all {
            configureForCodeArtifact(RepositoryMode.Resolve, ctx.tokenService)
        }

        settings.gradle.beforeProject {
            buildscript.repositories.all {
                configureForCodeArtifact(RepositoryMode.Resolve, ctx.tokenService)
            }
            pluginManager.apply(CodeArtifactPlugin::class)
        }
    }

    private fun applyToProject(project: Project) {
        val ctx = initPlugin(project.gradle)

        project.repositories.all {
            configureForCodeArtifact(RepositoryMode.Resolve, ctx.tokenService)
        }

        project.plugins.withType<MavenPublishPlugin> {
            project.configure<PublishingExtension> {
                repositories.all {
                    configureForCodeArtifact(RepositoryMode.Publish, ctx.tokenService)
                }
            }
        }

        project.tasks.withType<PublishPackageVersion>().configureEach {
            service.set(ctx.codeArtifactService)
        }
    }

    private fun RepositoryTransportFactory.addCodeArtifactResourceConnectorFactory(
        tokenResolver: TokenResolverBuildService
    ) {
        // Get the class of the transport factory
        val factoryClass = this::class.java

        // Find the registeredProtocols field
        val registeredProtocolsField = factoryClass.getDeclaredField("registeredProtocols")

        // Make the field accessible
        registeredProtocolsField.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val connectorFactories =
            registeredProtocolsField.get(this) as ArrayList<ResourceConnectorFactory>

        if (connectorFactories.none { it.supportedProtocols.contains("codeartifact") }) {
            val loggingInterceptor = HttpLoggingInterceptor(OkHttpLogger())
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.HEADERS)
            loggingInterceptor.redactHeader("Authorization")

            val httpClient =
                OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(false)
                    .addNetworkInterceptor(loggingInterceptor)
                    .addInterceptor(BrotliInterceptor)
                    .build()

            val proxyResolver = DefaultProxyResolver(systemVarResolver)

            connectorFactories.add(
                CodeArtifactResourceConnectorFactory(httpClient, proxyResolver, tokenResolver)
            )
        }
    }

    private class OkHttpLogger : HttpLoggingInterceptor.Logger {
        private val logger = Logging.getLogger(OkHttpLogger::class.java)

        override fun log(message: String) {
            logger.debug(message)
        }
    }

    private sealed class RepositoryMode {
        data object Resolve : RepositoryMode()

        data object Publish : RepositoryMode()
    }

    private fun ArtifactRepository.configureForCodeArtifact(
        repositoryMode: RepositoryMode,
        tokenService: Provider<TokenResolverBuildService>,
    ) {
        val repository = this as? DefaultMavenArtifactRepository ?: return
        val endpoint = repository.url.toCodeArtifactEndpointOrNull() ?: return

        val domainRegex =
            systemVarResolver.resolve("codeartifact.domains")?.toRegex() ?: Regex(".*")
        if (!domainRegex.matches(endpoint.domain)) return

        if (repositoryMode is RepositoryMode.Publish) {
            logger.info(
                "Configuring CodeArtifact publishing repository authentication: ${endpoint.url}"
            )

            repository.setConfiguredCredentials(createRepoCredentials(tokenService, endpoint))
            return
        }

        // force the use of codeartifact:// protocol for resolving
        repository.url = endpoint.toCodeArtifactProtocolUrl()

        // now everything is a codeartifact:// url; our ResourceConnector handles that protocol,
        // using OkHttpClient and authenticating with a CodeArtifact token.
    }

    private fun createRepoCredentials(
        tokenService: Provider<TokenResolverBuildService>,
        endpoint: CodeArtifactEndpoint,
    ): PasswordCredentials {
        val credentials = objects.newInstance<RepositoryCredentials>()
        credentials.usernameProp.set("aws")
        credentials.passwordProp.set(tokenService.map { it.resolve(endpoint).value })
        return credentials
    }
}

public fun RepositoryHandler.awsCodeArtifact(
    url: String,
    block: Action<MavenArtifactRepository> = Action {},
) {
    awsCodeArtifact(url.toCodeArtifactEndpoint(), block)
}

public fun RepositoryHandler.awsCodeArtifact(
    endpoint: CodeArtifactEndpoint,
    block: Action<MavenArtifactRepository> = Action {},
) {
    maven {
        this.name = endpoint.name
        this.url = endpoint.url
        block.execute(this)
    }
}

internal abstract class RepositoryCredentials : PasswordCredentials {

    @get:Input abstract val usernameProp: Property<String>

    @get:Internal abstract val passwordProp: Property<String>

    @Input override fun getUsername(): String = usernameProp.get()

    override fun setUsername(name: String?) = usernameProp.set(name)

    @Internal override fun getPassword(): String = passwordProp.get()

    override fun setPassword(pwd: String?) = passwordProp.set(pwd)
}
