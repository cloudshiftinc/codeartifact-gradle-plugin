package io.cloudshiftdev.gradle.codeartifact.resource

import io.cloudshiftdev.gradle.codeartifact.SystemVarResolver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import okhttp3.Protocol

class CodeArtifactHttpTest :
    FunSpec({
        fun resolver(value: String?) =
            object : SystemVarResolver {
                override fun resolve(vararg keys: String): String? = value
            }

        test("protocols default to OkHttp's") {
            CodeArtifactHttp.protocols(resolver(null)).shouldBeNull()
        }

        test("protocols parse and always include http/1.1") {
            CodeArtifactHttp.protocols(resolver("http/1.1")) shouldBe listOf(Protocol.HTTP_1_1)
            CodeArtifactHttp.protocols(resolver("h2")) shouldBe
                listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            CodeArtifactHttp.protocols(resolver("bogus, H2")) shouldBe
                listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        }

        test("client is shared for the same configuration") {
            CodeArtifactHttp.sharedClient(resolver(null)) shouldBeSameInstanceAs
                CodeArtifactHttp.sharedClient(resolver(null))
            val client = CodeArtifactHttp.sharedClient(resolver(null))
            client.readTimeoutMillis shouldBe 30_000
            client.connectTimeoutMillis shouldBe 30_000
        }
    })
