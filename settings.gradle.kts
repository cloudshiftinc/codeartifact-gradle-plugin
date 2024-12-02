rootProject.name = "codeartifact-plugin"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    require(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
        "This build requires Gradle to be run with at least Java 17"
    }
    repositories {
        repositories { maven("https://cache-redirector.jetbrains.com/plugins.gradle.org") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
    id("com.gradle.develocity") version "3.18.2"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { maven("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2") }
}

develocity {
    buildScan {
        publishing.onlyIf { !System.getenv("CI").isNullOrEmpty() }
        termsOfUseUrl = "https://gradle.com/terms-of-service"
        termsOfUseAgree = "yes"
    }
}
