package io.cloudshiftdev.gradle.codeartifact.internal.gradle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.URI
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.internal.artifacts.repositories.transport.NetworkingIssueVerifier
import org.gradle.api.resources.ResourceException
import org.gradle.authentication.Authentication
import org.gradle.internal.resource.HttpErrorStatusCodeException
import org.gradle.internal.resource.connector.ResourceConnectorFactory
import org.gradle.internal.resource.connector.ResourceConnectorSpecification
import org.gradle.internal.resource.transfer.ExternalResourceConnector
import org.gradle.testfixtures.ProjectBuilder

class GradleInternalsTest :
    FunSpec({
        val uri = URI("https://example.com/maven/repo/a.jar")
        val classLoader = GradleInternalsTest::class.java.classLoader

        test("selects the HTTP error factory for the running Gradle version") {
            Gradle99HttpErrorFactory(classLoader).available shouldBe true
            Gradle90HttpErrorFactory(classLoader).available shouldBe false

            val e =
                DefaultGradleInternals(classLoader)
                    .httpErrors
                    .create("GET", uri, 503, "Unavailable")
            e.shouldBeInstanceOf<HttpErrorStatusCodeException>()
            e.statusCode shouldBe 503
            e.message!! shouldContain "Received status code 503"
            NetworkingIssueVerifier.isLikelyTransientNetworkingIssue(e) shouldBe true
        }

        test("resolves the repository transport factory class") {
            ReflectiveResourceConnectorRegistry.resolveTransportFactoryClass(classLoader)
                .simpleName shouldBe "RepositoryTransportFactory"
        }

        test("registers a connector factory with Gradle's repository transport once") {
            val gradle = ProjectBuilder.builder().build().gradle
            val registry = DefaultGradleInternals(classLoader).connectorRegistry(gradle)
            var created = 0
            repeat(2) {
                registry.registerIfAbsent("test-proto") {
                    created++
                    TestConnectorFactory("test-proto")
                }
            }
            created shouldBe 1
        }

        test("sets credentials without resolving them") {
            val project = ProjectBuilder.builder().build()
            val repo = project.repositories.maven { url = project.uri("https://example.com/m2") }
            var resolved = false
            val credentials =
                object : PasswordCredentials {
                    override fun getUsername() = "aws"

                    override fun setUsername(userName: String?) = Unit

                    override fun getPassword(): String {
                        resolved = true
                        return "token"
                    }

                    override fun setPassword(password: String?) = Unit
                }

            DefaultGradleInternals(classLoader).setLazyCredentials(repo, credentials) shouldBe true
            resolved shouldBe false
            // actually attached, and still unresolved after reading the username
            repo.credentials.username shouldBe "aws"
            resolved shouldBe false
        }

        test("fallback decorator degrades to a plain ResourceException") {
            val factory = FallbackHttpErrorFactory({ _, _, _, _ -> error("boom") })
            val e = factory.create("HEAD", uri, 429, "Too Many Requests")
            e.shouldBeInstanceOf<ResourceException>()
            e.message!! shouldContain "status code 429"
        }
    })

private class TestConnectorFactory(private val protocol: String) : ResourceConnectorFactory {
    override fun getSupportedProtocols(): Set<String> = setOf(protocol)

    override fun getSupportedAuthentication(): Set<Class<out Authentication>> = emptySet()

    override fun createResourceConnector(
        connectionDetails: ResourceConnectorSpecification
    ): ExternalResourceConnector = error("not used")
}
