package io.cloudshiftdev.gradle.codeartifact

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpointOrNull
import io.cloudshiftdev.gradle.codeartifact.internal.gradle.DefaultGradleInternals
import io.cloudshiftdev.gradle.codeartifact.internal.gradle.GradleInternals
import io.cloudshiftdev.gradle.codeartifact.resource.CodeArtifactHttp
import io.cloudshiftdev.gradle.codeartifact.resource.CodeArtifactResourceConnectorFactory
import io.cloudshiftdev.gradle.codeartifact.token.DefaultTokenResolver
import java.io.File
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.PluginAware
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.withType

public abstract class CodeArtifactPlugin @Inject constructor(private val objects: ObjectFactory) :
    Plugin<PluginAware> {
    private val logger = Logging.getLogger(CodeArtifactPlugin::class.java)
    private val systemVarResolver = DefaultSystemVarResolver()
    private val gradleInternals: GradleInternals = DefaultGradleInternals.instance

    private companion object {
        /** `false` skips the (internal API) lazy credentials path; see README. */
        const val LAZY_CREDENTIALS_KEY = "codeartifact.lazyCredentials"
    }

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
        initPlugin(gradle)

        gradle.beforeSettings {
            buildscript.repositories.all {
                configureForCodeArtifact(RepositoryMode.Resolve, providers)
            }
            pluginManagement.repositories.all {
                configureForCodeArtifact(RepositoryMode.Resolve, providers)
            }
            pluginManager.apply(CodeArtifactPlugin::class)
        }
    }

    private fun initPlugin(gradle: Gradle) {
        val tokenResolver = DefaultTokenResolver.forGradleUserHome(gradle.gradleUserHomeDir)
        gradleInternals.connectorRegistry(gradle).registerIfAbsent("codeartifact") {
            CodeArtifactResourceConnectorFactory(
                okHttpClient = CodeArtifactHttp.sharedClient(systemVarResolver),
                proxyResolver = DefaultProxyResolver(systemVarResolver),
                tokenResolver = tokenResolver,
                httpErrors = gradleInternals.httpErrors,
            )
        }
    }

    private fun applyToSettings(settings: Settings) {
        initPlugin(settings.gradle)
        settings.pluginManagement.repositories.all {
            configureForCodeArtifact(RepositoryMode.Resolve, settings.providers)
        }

        settings.dependencyResolutionManagement.repositories.all {
            configureForCodeArtifact(RepositoryMode.Resolve, settings.providers)
        }

        // isolated (Isolated Projects-safe) action: captures nothing; applying the plugin to each
        // project configures that project's own repositories, including its buildscript ones
        settings.gradle.lifecycle.beforeProject { pluginManager.apply(CodeArtifactPlugin::class) }
    }

    private fun applyToProject(project: Project) {
        initPlugin(project.gradle)

        project.buildscript.repositories.all {
            configureForCodeArtifact(RepositoryMode.Resolve, project.providers)
        }

        project.repositories.all {
            configureForCodeArtifact(RepositoryMode.Resolve, project.providers)
        }

        project.plugins.withType<MavenPublishPlugin> {
            project.configure<PublishingExtension> {
                repositories.all {
                    configureForCodeArtifact(RepositoryMode.Publish(project), project.providers)
                }
            }
        }
    }

    private sealed class RepositoryMode {
        data object Resolve : RepositoryMode()

        class Publish(val project: Project) : RepositoryMode()
    }

    private fun ArtifactRepository.configureForCodeArtifact(
        repositoryMode: RepositoryMode,
        providers: ProviderFactory,
    ) {
        val repository = this as? MavenArtifactRepository ?: return
        val endpoint = repository.url.toCodeArtifactEndpointOrNull() ?: return

        val domainRegex =
            systemVarResolver.resolve("codeartifact.domains")?.toRegex() ?: Regex(".*")
        if (!domainRegex.matches(endpoint.domain)) return

        if (repositoryMode is RepositoryMode.Publish) {
            logger.info(
                "Configuring CodeArtifact publishing repository authentication: ${endpoint.url}"
            )

            val project = repositoryMode.project
            val credentials =
                createRepoCredentials(providers, endpoint, project.gradle.gradleUserHomeDir)
            val lazyCredentials =
                systemVarResolver.resolve(LAZY_CREDENTIALS_KEY)?.toBoolean() ?: true
            if (!lazyCredentials || !gradleInternals.setLazyCredentials(repository, credentials)) {
                if (lazyCredentials) {
                    logger.warn(
                        "Unable to set lazy CodeArtifact credentials on repository '{}'; " +
                            "the token will be fetched when a publish to it is scheduled",
                        repository.name,
                    )
                }
                setCredentialsWhenPublishing(project, repository, credentials)
            }
            return
        }

        // force the use of codeartifact:// protocol for resolving
        repository.url = endpoint.toCodeArtifactProtocolUrl()

        // now everything is a codeartifact:// url; our ResourceConnector handles that protocol,
        // using OkHttpClient and authenticating with a CodeArtifact token.
    }

    /**
     * Public-API fallback for lazy credentials: `credentials(Action)` copies values eagerly, so
     * only apply it (and so fetch the token) once the task graph shows a publish to [repository].
     * Builds that never publish, e.g. resolve-only CI jobs without AWS credentials, never fetch a
     * token.
     */
    private fun setCredentialsWhenPublishing(
        project: Project,
        repository: MavenArtifactRepository,
        credentials: PasswordCredentials,
    ) {
        project.gradle.taskGraph.whenReady {
            val publishing = allTasks.any {
                it is PublishToMavenRepository && it.repository === repository
            }
            if (publishing) {
                repository.credentials(PasswordCredentials::class.java) {
                    username = credentials.username
                    password = credentials.password
                }
            }
        }
    }

    private fun createRepoCredentials(
        providers: ProviderFactory,
        endpoint: CodeArtifactEndpoint,
        gradleUserHome: File,
    ): PasswordCredentials {
        val credentials = objects.newInstance<RepositoryCredentials>()
        credentials.usernameProp.set("aws")
        credentials.passwordProp.set(providers.codeArtifactToken(endpoint, gradleUserHome))
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
