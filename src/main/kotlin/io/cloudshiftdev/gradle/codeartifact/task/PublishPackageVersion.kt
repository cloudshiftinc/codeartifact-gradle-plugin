package io.cloudshiftdev.gradle.codeartifact.task

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.GenericPackage
import io.cloudshiftdev.gradle.codeartifact.service.CodeArtifactService
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "This task runs infrequently and deals with potentially large files")
public abstract class PublishPackageVersion : DefaultTask() {

    @get:Input public abstract val repositoryUrl: Property<String>

    @get:Input public abstract val packageName: Property<String>

    @get:Input public abstract val packageNamespace: Property<String>

    @get:Input public abstract val packageVersion: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val artifacts: ConfigurableFileCollection

    @get:Internal internal abstract val service: Property<CodeArtifactService>

    @TaskAction
    public fun publish() {
        val genericPackage =
            GenericPackage(
                packageName.get(),
                packageNamespace.get(),
                packageVersion.get(),
                artifacts.map { GenericPackage.Asset(it.name, it) },
            )

        service
            .get()
            .publishPackageVersion(genericPackage, repositoryUrl.get().toCodeArtifactEndpoint())
    }
}
