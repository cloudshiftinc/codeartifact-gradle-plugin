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

    implementation(platform("aws.smithy.kotlin:bom:1.5.15"))
    implementation("aws.smithy.kotlin:http-client-engine-crt")
}

gradlePlugin {
    website = "https://github.com/cloudshiftinc/codeartifact-gradle-plugin"
    vcsUrl = "https://github.com/cloudshiftinc/codeartifact-gradle-plugin"
    plugins {
        create("codeartifact") {
            id = "io.cloudshiftdev.codeartifact"
            displayName = "Plugin providing CodeArtifact repositories support"
            description = "Settings plugin providing CodeArtifact repositories support"
            implementationClass = "io.cloudshiftdev.gradle.codeartifact.CodeArtifactPlugin"
            tags = listOf("codeartifact", "aws", "repository")
        }
    }
}

signing {
    val signingKey = findProperty("SIGNING_KEY") as? String
    val signingPwd = findProperty("SIGNING_PWD") as? String
    useInMemoryPgpKeys(signingKey, signingPwd)
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

val ktfmtVersion = ktfmtVersion()

spotless {
    encoding = StandardCharsets.UTF_8

    kotlinGradle { ktfmt(ktfmtVersion).kotlinlangStyle() }

    kotlin { ktfmt(ktfmtVersion).kotlinlangStyle() }
}

internal fun Project.ktfmtVersion(): String {
    val resourceUri = this::class.java.getResource("/codeartifact-plugin/ktfmt-version.txt")
    return resourceUri?.let { resources.text.fromUri(it).asString() } ?: "0.58"
}

tasks {
    val persistKtfmtVersion by registering {
        inputs.property("ktfmtVersion", libs.ktfmt)
        outputs.files(layout.buildDirectory.file("ktfmt-version.txt"))
        doLast {
            outputs.files.singleFile.writeText(
                inputs.properties["ktfmtVersion"].toString().substringAfterLast(":")
            )
        }
    }

    named<ProcessResources>("processResources") {
        from(persistKtfmtVersion) { into("codeartifact-plugin") }
    }

    withType<ValidatePlugins>().configureEach {
        enableStricterValidation = true
        failOnWarning = true
    }

    withType<PublishToMavenRepository>().configureEach {
        onlyIf {
            when {
                System.getenv("CI") != null ->
                    when {
                        version.toString().endsWith("SNAPSHOT") ->
                            repository.name.endsWith("Snapshot")
                        else -> repository.name.endsWith("Release")
                    }

                else -> repository.name.endsWith("Local")
            }
        }
    }
}

testing {
    suites {
        val test by
            getting(JvmTestSuite::class) {
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
                            outputs.upToDateWhen { false }
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
