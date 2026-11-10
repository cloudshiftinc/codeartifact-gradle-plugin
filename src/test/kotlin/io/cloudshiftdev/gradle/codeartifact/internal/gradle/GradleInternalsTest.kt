package io.cloudshiftdev.gradle.codeartifact.internal.gradle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.URI
import org.gradle.api.internal.artifacts.repositories.transport.NetworkingIssueVerifier
import org.gradle.api.resources.ResourceException
import org.gradle.internal.resource.HttpErrorStatusCodeException

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

        test("fallback decorator degrades to a plain ResourceException") {
            val factory = FallbackHttpErrorFactory({ _, _, _, _ -> error("boom") })
            val e = factory.create("HEAD", uri, 429, "Too Many Requests")
            e.shouldBeInstanceOf<ResourceException>()
            e.message!! shouldContain "status code 429"
        }
    })
