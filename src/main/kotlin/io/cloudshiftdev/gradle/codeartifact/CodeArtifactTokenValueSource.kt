package io.cloudshiftdev.gradle.codeartifact

import io.cloudshiftdev.gradle.codeartifact.token.DefaultTokenResolver
import java.io.File
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.of

internal abstract class CodeArtifactTokenValueSource :
    ValueSource<String, CodeArtifactTokenValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val endpoint: Property<CodeArtifactEndpoint>

        /** Optional; captured at configuration time so it survives configuration-cache reuse. */
        val gradleUserHome: Property<File>
    }

    override fun obtain(): String =
        DefaultTokenResolver.forGradleUserHome(parameters.gradleUserHome.orNull)
            .resolve(parameters.endpoint.get())
            .value
}

/**
 * Token provider for [endpoint], cached under the Gradle user home taken from `GRADLE_USER_HOME` /
 * `-Dgradle.user.home`. A `-g`/`--gradle-user-home` command-line option is not visible here; prefer
 * the overload taking [Gradle], e.g. `providers.codeArtifactToken(endpoint, gradle)`.
 */
public fun ProviderFactory.codeArtifactToken(endpoint: CodeArtifactEndpoint): Provider<String> =
    codeArtifactToken(endpoint, gradleUserHome = null)

/**
 * Token provider for [endpoint], cached under [gradle]'s user home (honouring
 * `-g`/`--gradle-user-home`), e.g. `providers.codeArtifactToken(endpoint, gradle)`.
 */
public fun ProviderFactory.codeArtifactToken(
    endpoint: CodeArtifactEndpoint,
    gradle: Gradle,
): Provider<String> = codeArtifactToken(endpoint, gradle.gradleUserHomeDir)

internal fun ProviderFactory.codeArtifactToken(
    endpoint: CodeArtifactEndpoint,
    gradleUserHome: File?,
): Provider<String> =
    of(CodeArtifactTokenValueSource::class) {
        parameters {
            this.endpoint = endpoint
            this.gradleUserHome = gradleUserHome
        }
    }
