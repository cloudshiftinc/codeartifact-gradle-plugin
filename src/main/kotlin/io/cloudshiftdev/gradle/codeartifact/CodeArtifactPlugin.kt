package io.cloudshiftdev.gradle.codeartifact

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpointOrNull
import java.net.URI
import javax.inject.Inject
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.initialization.Settings
import org.gradle.api.internal.artifacts.repositories.DefaultMavenArtifactRepository
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.PluginAware
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.of
import org.gradle.kotlin.dsl.withType

public abstract class CodeArtifactPlugin @Inject constructor(private val objects: ObjectFactory) :
    Plugin<PluginAware> {
    private val logger = Logging.getLogger(CodeArtifactPlugin::class.java)

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
        gradle.beforeSettings {
            buildscript.repositories.all { configureCodeArtifactRepository(this, providers) }
            pluginManagement.repositories.all { configureCodeArtifactRepository(this, providers) }
            pluginManager.apply(CodeArtifactPlugin::class)
        }
    }

    private fun applyToSettings(settings: Settings) {
        settings.pluginManagement.repositories.all {
            configureCodeArtifactRepository(this, settings.providers)
        }

        settings.dependencyResolutionManagement.repositories.all {
            configureCodeArtifactRepository(this, settings.providers)
        }

        settings.gradle.beforeProject {
            buildscript.repositories.all { configureCodeArtifactRepository(this, providers) }
            pluginManager.apply(CodeArtifactPlugin::class)
        }
    }

    private fun applyToProject(project: Project) {
        project.repositories.all { configureCodeArtifactRepository(this, project.providers) }

        project.plugins.withType<MavenPublishPlugin> {
            project.configure<PublishingExtension> {
                repositories.all {
                    configureCodeArtifactRepository(this, project.providers, useProxy = false)
                }
            }
        }
    }

    private fun configureCodeArtifactRepository(
        repository: ArtifactRepository,
        providers: ProviderFactory,
        useProxy: Boolean = true
    ) {
        if (!shouldConfigureCodeArtifactRepository(repository)) return

        val endpoint = repository.url.toCodeArtifactEndpoint()
        logger.info("Configuring CodeArtifact repository @ ${endpoint.url}")

        val tokenProvider =
            providers.of(CodeArtifactTokenValueSource::class) {
                parameters { this.endpoint = endpoint }
            }
        repository.setConfiguredCredentials(createRepoCredentials(tokenProvider))

        val proxyEnabled = resolveSystemVar("codeartifact.proxy.enabled")?.toBoolean() ?: true

        if (useProxy && proxyEnabled) {
            val key =
                "codeartifact.${endpoint.domain}-${endpoint.domainOwner}-${endpoint.region}.proxy.base-url"
            resolveSystemVar(key)?.let { proxyBaseUrl ->
                val proxyUrl = URI("${proxyBaseUrl}${endpoint.url.path}")
                logger.info(
                    "Using proxy for CodeArtifact repository: $proxyUrl -> ${endpoint.url}"
                )
                repository.url = proxyUrl
            } ?: run {
                logger.info("No proxy configured for CodeArtifact repository: ${endpoint.url}")
            }
        }
    }

    @OptIn(ExperimentalContracts::class)
    private fun shouldConfigureCodeArtifactRepository(repository: ArtifactRepository): Boolean {
        contract { returns(true) implies (repository is DefaultMavenArtifactRepository) }
        if (repository !is DefaultMavenArtifactRepository) return false

        val domainRegex = resolveSystemVar("codeartifact.domains")?.toRegex() ?: Regex(".*")

        val endpoint = repository.url.toCodeArtifactEndpointOrNull()
        return when {
            endpoint == null -> false
            domainRegex.matches(endpoint.domain) -> true
            else -> false
        }
    }

    private fun createRepoCredentials(
        codeArtifactTokenProvider: Provider<String>
    ): PasswordCredentials {
        val credentials = objects.newInstance<RepositoryCredentials>()
        credentials.usernameProp.set("aws")
        credentials.passwordProp.set(codeArtifactTokenProvider)
        return credentials
    }
}

public fun RepositoryHandler.awsCodeArtifact(
    url: String,
    block: Action<MavenArtifactRepository> = Action {}
) {
    awsCodeArtifact(url.toCodeArtifactEndpoint(), block)
}

public fun RepositoryHandler.awsCodeArtifact(
    endpoint: CodeArtifactEndpoint,
    block: Action<MavenArtifactRepository> = Action {}
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
