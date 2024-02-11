
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
    signing
    id("com.gradle.plugin-publish") version "1.2.1"
    id("com.ncorti.ktfmt.gradle") version "0.17.0"
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
            implementationClass = "io.cloudshiftdev.gradle.codeartifact.codeartifact.CodeArtifactPlugin"
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

tasks {

    withType<ValidatePlugins>().configureEach {
        enableStricterValidation.set(true)
        failOnWarning.set(true)
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
