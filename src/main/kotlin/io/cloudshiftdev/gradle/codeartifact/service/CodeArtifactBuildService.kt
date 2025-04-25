package io.cloudshiftdev.gradle.codeartifact.service

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.CodeArtifactToken
import io.cloudshiftdev.gradle.codeartifact.GenericPackage
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.kotlin.dsl.registerIfAbsent

internal abstract class CodeArtifactBuildService :
    BuildService<BuildServiceParameters.None>, CodeArtifactService {
    private val service: CodeArtifactService = DefaultCodeArtifactService()

    override fun getAuthorizationToken(endpoint: CodeArtifactEndpoint): CodeArtifactToken =
        service.getAuthorizationToken(endpoint)

    override fun publishPackageVersion(
        genericPackage: GenericPackage,
        endpoint: CodeArtifactEndpoint,
    ) {
        service.publishPackageVersion(genericPackage, endpoint)
    }
}

internal fun Gradle.registerCodeArtifactBuildService(): Provider<CodeArtifactBuildService> {
    return gradle.sharedServices.registerIfAbsent(
        "codeArtifactService",
        CodeArtifactBuildService::class,
    )
}
