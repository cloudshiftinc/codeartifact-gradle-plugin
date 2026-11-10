package io.cloudshiftdev.gradle.codeartifact.token

import io.cloudshiftdev.gradle.codeartifact.codeArtifactToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MemoryCacheTokenResolverTest :
    FunSpec({
        test("caching works") {
            val token = codeArtifactToken()

            val delegate = mockk<TokenResolver>()
            coEvery { delegate.resolve(token.endpoint) } returns token

            val cache = MemoryCacheTokenResolver(delegate)
            val resolvedToken = cache.resolve(token.endpoint)
            resolvedToken shouldBe token
            repeat(10) { cache.resolve(token.endpoint) }

            coVerify(exactly = 1) { delegate.resolve(token.endpoint) }
        }

        test("cache returns new token when expired") {
            val token = codeArtifactToken()
            val expiredToken = codeArtifactToken(expired = true)

            val delegate = mockk<TokenResolver>()
            coEvery { delegate.resolve(token.endpoint) } returns expiredToken

            val cache = MemoryCacheTokenResolver(delegate)

            repeat(10) { cache.resolve(token.endpoint) }

            coVerify(exactly = 10) { delegate.resolve(token.endpoint) }
        }

        test("concurrent first requests fetch a single token") {
            val token = codeArtifactToken()
            val calls = AtomicInteger()
            val cache = MemoryCacheTokenResolver {
                calls.incrementAndGet()
                Thread.sleep(50)
                token
            }

            val pool = Executors.newFixedThreadPool(16)
            try {
                val start = CountDownLatch(1)
                val futures =
                    (1..50).map {
                        pool.submit<Any> {
                            start.await()
                            cache.resolve(token.endpoint)
                        }
                    }
                start.countDown()
                futures.forEach { it.get() shouldBe token }
            } finally {
                pool.shutdown()
            }
            calls.get() shouldBe 1
        }

        test("token expiry is evaluated at use, not at construction") {
            val token =
                codeArtifactToken()
                    .copy(expiration = Instant.now().plus(20, ChronoUnit.MINUTES).plusMillis(200))
            token.expired shouldBe false
            Thread.sleep(400)
            token.expired shouldBe true
        }
    })
