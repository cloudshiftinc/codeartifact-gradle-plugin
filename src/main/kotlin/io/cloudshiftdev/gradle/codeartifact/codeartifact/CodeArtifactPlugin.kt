package io.cloudshiftdev.gradle.codeartifact.codeartifact

import io.cloudshiftdev.gradle.codeartifact.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import java.net.URI
import javax.inject.Inject
import net.pearx.kasechange.toPascalCase
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.initialization.Settings
import org.gradle.api.internal.artifacts.repositories.DefaultMavenArtifactRepository
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.of
import org.gradle.kotlin.dsl.withType

public abstract class CodeArtifactPlugin @Inject constructor(private val objects: ObjectFactory) :
    Plugin<Settings> {
    private val logger = Logging.getLogger(CodeArtifactPlugin::class.java)

    override fun apply(settings: Settings): Unit =
        settings.run {
            dependencyResolutionManagement.repositories.all {
                configureCodeArtifactRepository(this, providers)
            }

            gradle.beforeProject {
                plugins.withType<MavenPublishPlugin> {
                    configure<PublishingExtension> {
                        repositories.all { configureCodeArtifactRepository(this, providers) }
                    }
                }
            }
        }

    private fun configureCodeArtifactRepository(
        repository: ArtifactRepository,
        providers: ProviderFactory,
    ) {
        if (repository !is MavenArtifactRepository) {
            return
        }

        val endpoint = repository.url.toCodeArtifactEndpoint()
        when {
            endpoint == null -> return
            repository is DefaultMavenArtifactRepository -> {
                val tokenProvider =
                    providers.of(CodeArtifactTokenValueSource::class) {
                        parameters { this.endpoint = endpoint }
                    }
                repository.setConfiguredCredentials(createRepoCredentials(tokenProvider))
            }
            else -> {}
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
    val endpoint = url.toCodeArtifactEndpoint() ?: error("Invalid CodeArtifact URL: $url")
    maven {
        this.name = "${endpoint.domain}-${endpoint.repository}".toPascalCase()
        this.url = URI(url)
        block.execute(this)
    }
}

public fun Project.isSnapshotVersion(): Boolean = version.toString().endsWith("SNAPSHOT")

public fun MavenArtifactRepository.isSnapshotRepo(): Boolean = name.endsWith("Snapshot")

public fun MavenArtifactRepository.isReleaseRepo(): Boolean = name.endsWith("Release")

public fun MavenArtifactRepository.isLocalRepo(): Boolean = name.endsWith("Local")

internal abstract class RepositoryCredentials : PasswordCredentials {

    @get:Input abstract val usernameProp: Property<String>

    @get:Internal abstract val passwordProp: Property<String>

    @Input override fun getUsername(): String = usernameProp.get()

    override fun setUsername(name: String?) = usernameProp.set(name)

    @Internal override fun getPassword(): String = passwordProp.get()

    override fun setPassword(pwd: String?) = passwordProp.set(pwd)
}
