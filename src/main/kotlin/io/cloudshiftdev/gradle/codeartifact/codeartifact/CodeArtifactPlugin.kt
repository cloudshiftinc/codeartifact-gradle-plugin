package io.cloudshiftdev.gradle.codeartifact.codeartifact

import java.net.URI
import net.pearx.kasechange.toPascalCase
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.registerIfAbsent
import org.gradle.kotlin.dsl.withType

public abstract class CodeArtifactPlugin : Plugin<Settings> {
    private val logger = Logging.getLogger(CodeArtifactPlugin::class.java)

    override fun apply(settings: Settings): Unit =
        settings.run {
            val codeArtifactTokenProvider =
                settings.gradle.sharedServices.registerIfAbsent(
                    "codeArtifactTokenProvider",
                    CodeArtifactTokenProvider::class
                ) {
                    parameters {}
                }

            dependencyResolutionManagement.repositories.all {
                configureCodeArtifactRepository(this, codeArtifactTokenProvider)
            }

            gradle.beforeProject {
                plugins.withType<MavenPublishPlugin> {
                    configure<PublishingExtension> {
                        repositories.all {
                            configureCodeArtifactRepository(this, codeArtifactTokenProvider)
                        }
                    }
                }
            }
        }

    private fun configureCodeArtifactRepository(
        repository: ArtifactRepository,
        codeArtifactTokenProvider: Provider<CodeArtifactTokenProvider>
    ) {
        if (repository !is MavenArtifactRepository) {
            return
        }

        val endpoint = CodeArtifactEndpoint.fromUrl(repository.url)
        when {
            endpoint == null && repository.url.toString().contains("d.codeartifact") -> {
                throw GradleException("Invalid CodeArtifact URL: ${repository.url}")
            }
            endpoint == null -> return
            else -> {
                repository.url = endpoint.url
                repository.credentials {
                    username = "aws"
                    password = codeArtifactTokenProvider.get().tokenForEndpoint(endpoint)
                }
            }
        }
    }
}

public fun RepositoryHandler.awsCodeArtifact(
    url: String,
    block: Action<MavenArtifactRepository> = Action {}
) {
    val endpoint = CodeArtifactEndpoint.fromUrl(URI(url)) ?: error("Invalid CodeArtifact URL: $url")
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
