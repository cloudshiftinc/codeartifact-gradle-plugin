rootProject.name = "codeartifact-plugin"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    require(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
        "This build requires Gradle to be run with at least Java 17"
    }
//    includeBuild("build-logic")
    repositories {
        repositories { maven("https://cache-redirector.jetbrains.com/plugins.gradle.org") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
    id("com.gradle.enterprise") version "3.14.1"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2")
    }
}

gradleEnterprise {
    if (System.getenv("CI") != null) {
        buildScan {
            publishAlways()
            termsOfServiceUrl = "https://gradle.com/terms-of-service"
            termsOfServiceAgree = "yes"
        }
    }
}
