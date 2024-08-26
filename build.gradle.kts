
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
    signing
    id("com.gradle.plugin-publish") version "1.2.1"
    id("com.ncorti.ktfmt.gradle") version "0.20.0"
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

    implementation(libs.pearx.kasechange)
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
    jvmToolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

ktfmt {
    kotlinLangStyle()
}

tasks {

    withType<ValidatePlugins>().configureEach {
        enableStricterValidation = true
        failOnWarning = true
    }

    named<KotlinCompile>("compileKotlin") {
        kotlinOptions {
            apiVersion = "1.9"
            languageVersion = "1.9"
        }
    }

    withType<PublishToMavenRepository>().configureEach {
        onlyIf {
            when {
                System.getenv("CI") != null -> when {
                    version.toString().endsWith("SNAPSHOT") -> repository.name.endsWith("Snapshot")
                    else -> repository.name.endsWith("Release")
                }

                else -> repository.name.endsWith("Local")
            }
        }
    }
}


testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(platform(libs.kotest.bom))
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.assertions.json)
                implementation(libs.kotest.framework.datatest)
                implementation(libs.kotest.property)
                implementation(libs.kotest.runner.junit5)
                implementation("io.mockk:mockk:1.13.12")
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
                                    TestLogEvent.STANDARD_ERROR
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

