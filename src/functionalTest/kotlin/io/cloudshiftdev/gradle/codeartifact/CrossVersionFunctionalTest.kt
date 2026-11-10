package io.cloudshiftdev.gradle.codeartifact

import io.cloudshiftdev.gradle.codeartifact.CodeArtifactEndpoint.Companion.toCodeArtifactEndpoint
import io.cloudshiftdev.gradle.codeartifact.token.LocalCache
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * Applies the plugin in a real build on each supported Gradle version and resolves a dependency
 * through the `codeartifact://` transport. This is what exercises the version-specific branches in
 * `internal/gradle` (transport factory location, HTTP error exception type, lazy credentials).
 */
class CrossVersionFunctionalTest :
    FunSpec({
        val gradleVersions =
            System.getProperty("functionalTest.gradleVersions")
                .orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)

        val repoUrl =
            "https://test-domain-123456789012.d.codeartifact.eu-west-1.amazonaws.com/maven/env-data/"

        // The token cache lives under the Gradle user home, which for TestKit builds is the TestKit
        // dir; pre-seed it so no AWS call is made.
        val testKitDir = File(System.getProperty("functionalTest.testKitDir"))
        LocalCache(testKitDir.resolve("caches/codeartifact")).load(
            repoUrl.toCodeArtifactEndpoint()
        ) {
            CodeArtifactToken(
                endpoint = repoUrl.toCodeArtifactEndpoint(),
                value = "test-token",
                expiration = Instant.now().plusSeconds(12 * 3600),
            )
        }

        gradleVersions.forEach { gradleVersion ->
            test("resolves through codeartifact:// and retries 5xx on Gradle $gradleVersion") {
                // unique coordinates per run: TestKit shares a dependency cache across builds
                val group = "com.example.ft${System.nanoTime()}"
                val server = MockWebServer()
                val dispatcher = RepositoryDispatcher(group)
                server.dispatcher = dispatcher
                server.start()
                val projectDir = Files.createTempDirectory("codeartifact-ft").toFile()
                try {
                    projectDir.writeFixture(repoUrl, group)

                    val result =
                        GradleRunner.create()
                            .withGradleVersion(gradleVersion)
                            .withProjectDir(projectDir)
                            .withPluginClasspath()
                            .withTestKitDir(testKitDir)
                            .withArguments(
                                "resolveDeps",
                                "--stacktrace",
                                "-Dcodeartifact.proxy.base-url=${server.url("/")}",
                            )
                            .forwardOutput()
                            .build()

                    result.task(":resolveDeps")?.outcome shouldBe TaskOutcome.SUCCESS
                    result.output shouldContain "RESOLVED: lib-1.0.jar"
                    // the publishing repository got lazy (reflective) credentials, not the
                    // eager fallback
                    result.output shouldNotContain "Unable to set lazy CodeArtifact credentials"

                    // first POM request got a 503: Gradle only retries if our transport raised
                    // the HTTP error type this Gradle version recognises
                    dispatcher.count(dispatcher.pomPath) shouldBe 2
                    dispatcher.authorization shouldBe "Bearer test-token"
                } finally {
                    server.close()
                    projectDir.deleteRecursively()
                }
            }
        }

        // An unseeded domain: any token fetch for it would show "Fetching CodeArtifact token".
        val unseededRepoUrl =
            "https://other-domain-123456789012.d.codeartifact.eu-west-1.amazonaws.com/maven/env-data/"

        gradleVersions.forEach { gradleVersion ->
            test("fallback credentials are only fetched when publishing on Gradle $gradleVersion") {
                val group = "com.example.ft${System.nanoTime()}"
                val server = MockWebServer()
                server.dispatcher = RepositoryDispatcher(group, failFirstPom = false)
                server.start()
                val projectDir = Files.createTempDirectory("codeartifact-ft").toFile()
                fun run(publishRepoUrl: String, vararg tasks: String): String {
                    projectDir.writeFixture(repoUrl, group, publishRepoUrl)
                    return GradleRunner.create()
                        .withGradleVersion(gradleVersion)
                        .withProjectDir(projectDir)
                        .withPluginClasspath()
                        .withTestKitDir(testKitDir)
                        .withArguments(
                            *tasks,
                            "--stacktrace",
                            "-Dcodeartifact.proxy.base-url=${server.url("/")}",
                            "-Dcodeartifact.lazyCredentials=false",
                        )
                        .forwardOutput()
                        .build()
                        .output
                }
                try {
                    // not publishing: the (unseeded) publish repository's token is never fetched
                    val resolveOutput = run(unseededRepoUrl, "resolveDeps")
                    resolveOutput shouldContain "RESOLVED: lib-1.0.jar"
                    resolveOutput shouldNotContain "Fetching CodeArtifact token"
                    resolveOutput shouldNotContain "PUBLISH-CREDS"

                    // publish scheduled: credentials are attached once the task graph is ready
                    val publishOutput = run(repoUrl, "publish", "--dry-run")
                    publishOutput shouldContain "PUBLISH-CREDS: aws:test-token"
                } finally {
                    server.close()
                    projectDir.deleteRecursively()
                }
            }
        }
    })

private class RepositoryDispatcher(group: String, private val failFirstPom: Boolean = true) :
    Dispatcher() {
    private val repoPath = "/maven/env-data/${group.replace('.', '/')}/lib/1.0"
    val pomPath = "$repoPath/lib-1.0.pom"
    private val jarPath = "$repoPath/lib-1.0.jar"
    private val pom = pom(group)

    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile var authorization: String? = null

    fun count(path: String): Int = counts[path]?.get() ?: 0

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val n = counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
        authorization = request.headers["Authorization"]
        return when (path) {
            pomPath ->
                if (n == 1 && failFirstPom) MockResponse.Builder().code(503).build()
                else MockResponse.Builder().code(200).body(pom).build()
            jarPath -> MockResponse.Builder().code(200).body("not really a jar").build()
            else -> MockResponse.Builder().code(404).build()
        }
    }
}

private fun pom(group: String) =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0">
      <modelVersion>4.0.0</modelVersion>
      <groupId>$group</groupId>
      <artifactId>lib</artifactId>
      <version>1.0</version>
    </project>
    """
        .trimIndent()

private fun File.writeFixture(repoUrl: String, group: String, publishRepoUrl: String = repoUrl) {
    resolve("settings.gradle.kts")
        .writeText(
            """
            plugins { id("io.cloudshiftdev.codeartifact") }

            dependencyResolutionManagement {
                repositories { maven { url = uri("$repoUrl") } }
            }

            rootProject.name = "fixture"
            """
                .trimIndent()
        )
    resolve("build.gradle.kts")
        .writeText(
            """
            plugins {
                `java-library`
                `maven-publish`
            }

            dependencies { implementation("$group:lib:1.0") }

            publishing {
                repositories { maven { url = uri("$publishRepoUrl") } }
                publications { create<MavenPublication>("lib") { from(components["java"]) } }
            }

            // Registered after the plugin's own listener, so it sees the credentials it set.
            gradle.taskGraph.whenReady {
                if (allTasks.any { it is PublishToMavenRepository }) {
                    val repo = publishing.repositories.single() as MavenArtifactRepository
                    println("PUBLISH-CREDS: " + repo.credentials.username + ":" + repo.credentials.password)
                }
            }

            tasks.register("resolveDeps") {
                val classpath = configurations.compileClasspath
                doLast { classpath.get().files.forEach { println("RESOLVED: " + it.name) } }
            }
            """
                .trimIndent()
        )
}
