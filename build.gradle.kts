import java.nio.charset.StandardCharsets
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    signing
    alias(libs.plugins.publish)
    alias(libs.plugins.spotless)
}

dependencies {
    implementation(platform(libs.aws.sdk.kotlin.v1.bom))
    implementation(libs.aws.sdk.kotlin.v1.codeartifact)

    implementation(libs.google.tink)

    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.okhttp.brotli)

    implementation(libs.pearx.kasechange)

    implementation(libs.aws.smithy.kotlin.http.client.engine.okhttp)
}

java { consistentResolution { useCompileClasspathVersions() } }

// The Kotlin Gradle Plugin (pinned by `kotlin-dsl`) resolves Bouncy Castle 1.84 for its internal
// publishing-validation tasks; it is build-time only and never shipped. Align it with the version
// bundled in the Gradle distribution to clear GHSA-9pwp-9qqc-pr26 / GHSA-qp49-qgx5-5m26.
configurations
    .matching { it.name == "kotlinBouncyCastleConfiguration" }
    .configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle") {
                useVersion("1.86")
                because("Bouncy Castle < 1.85 has known vulnerabilities")
            }
        }
    }

gradlePlugin {
    website = "https://github.com/cloudshiftinc/codeartifact-gradle-plugin"
    vcsUrl = "https://github.com/cloudshiftinc/codeartifact-gradle-plugin"
    plugins {
        create("codeartifact") {
            id = "io.cloudshiftdev.codeartifact"
            displayName = "Plugin providing CodeArtifact repositories support"
            description =
                "Settings, project and init-script plugin providing CodeArtifact repositories support"
            implementationClass = "io.cloudshiftdev.gradle.codeartifact.CodeArtifactPlugin"
            tags = listOf("codeartifact", "aws", "repository")
        }
    }
}

signing {
    useInMemoryPgpKeys(
        providers.gradleProperty("SIGNING_KEY").orNull,
        providers.gradleProperty("SIGNING_PWD").orNull,
    )
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        val additionalArgs =
            listOfNotNull(
                    "jdk-release=21", // https://jakewharton.com/kotlins-jdk-release-compatibility-flag/
                    "jsr305=strict",
                )
                .map { "-X$it" }
        freeCompilerArgs.addAll(additionalArgs)
    }
}

val ktfmtVersion = libs.versions.ktfmt.get()

spotless {
    encoding = StandardCharsets.UTF_8

    kotlinGradle { ktfmt(ktfmtVersion).kotlinlangStyle() }

    kotlin { ktfmt(ktfmtVersion).kotlinlangStyle() }
}

tasks {
    withType<ValidatePlugins>().configureEach {
        enableStricterValidation = true
        failOnWarning = true
    }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter()
            dependencies {
                implementation(platform(libs.kotest.bom))
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.assertions.json)
                implementation(libs.kotest.property)
                implementation(libs.kotest.runner.junit5)
                implementation(libs.mockk)
                implementation(libs.okhttp.mockwebserver)
            }
            targets {
                all {
                    testTask.configure {
                        testLogging {
                            events =
                                setOf(
                                    TestLogEvent.FAILED,
                                    TestLogEvent.PASSED,
                                    TestLogEvent.SKIPPED,
                                    TestLogEvent.STANDARD_OUT,
                                    TestLogEvent.STANDARD_ERROR,
                                )
                            exceptionFormat = TestExceptionFormat.FULL
                            showExceptions = true
                            showCauses = true
                            showStackTraces = true
                        }
                    }
                }
            }
        }
    }
}
