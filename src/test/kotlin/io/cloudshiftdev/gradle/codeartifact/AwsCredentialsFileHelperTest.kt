package io.cloudshiftdev.gradle.codeartifact

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.writeText

class AwsCredentialsFileHelperTest : FunSpec() {
    init {
        test("hasStaticCredentials returns true when profile has static credentials") {
            val credentialsFile = Files.createTempFile("aws-credentials", ".ini")
            credentialsFile.writeText(
                """
                [default]
                aws_access_key_id = AKIAIOSFODNN7EXAMPLE
                aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
                """
                    .trimIndent()
            )

            val helper = AwsCredentialsFileHelper(credentialsFile)
            helper.hasStaticCredentials("default").shouldBeTrue()

            Files.deleteIfExists(credentialsFile)
        }

        test("hasStaticCredentials returns false when profile has no static credentials") {
            val credentialsFile = Files.createTempFile("aws-credentials", ".ini")
            credentialsFile.writeText(
                """
                [default]
                # No credentials here
                """
                    .trimIndent()
            )

            val helper = AwsCredentialsFileHelper(credentialsFile)
            helper.hasStaticCredentials("default").shouldBeFalse()

            Files.deleteIfExists(credentialsFile)
        }

        test("hasStaticCredentials returns false when profile does not exist") {
            val credentialsFile = Files.createTempFile("aws-credentials", ".ini")
            credentialsFile.writeText(
                """
                [other-profile]
                aws_access_key_id = AKIAIOSFODNN7EXAMPLE
                aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
                """
                    .trimIndent()
            )

            val helper = AwsCredentialsFileHelper(credentialsFile)
            helper.hasStaticCredentials("default").shouldBeFalse()

            Files.deleteIfExists(credentialsFile)
        }

        test("hasStaticCredentials returns false when credentials file does not exist") {
            val credentialsFile = Files.createTempFile("aws-credentials", ".ini")
            Files.deleteIfExists(credentialsFile)

            val helper = AwsCredentialsFileHelper(credentialsFile)
            helper.hasStaticCredentials("default").shouldBeFalse()
        }

        test("getStaticCredentials returns credentials when present") {
            val credentialsFile = Files.createTempFile("aws-credentials", ".ini")
            credentialsFile.writeText(
                """
                [myprofile]
                aws_access_key_id = AKIAIOSFODNN7EXAMPLE
                aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
                aws_session_token = FwoGZXIvYXdzEBYaDH...
                """
                    .trimIndent()
            )

            val helper = AwsCredentialsFileHelper(credentialsFile)
            val creds = helper.getStaticCredentials("myprofile").shouldNotBeNull()
            creds.accessKeyId shouldBe "AKIAIOSFODNN7EXAMPLE"
            creds.secretAccessKey shouldBe "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
            creds.sessionToken shouldBe "FwoGZXIvYXdzEBYaDH..."

            Files.deleteIfExists(credentialsFile)
        }

        test("getStaticCredentials returns null session token when not present") {
            val credentialsFile = Files.createTempFile("aws-credentials", ".ini")
            credentialsFile.writeText(
                """
                [myprofile]
                aws_access_key_id = AKIAIOSFODNN7EXAMPLE
                aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
                """
                    .trimIndent()
            )

            val helper = AwsCredentialsFileHelper(credentialsFile)
            val creds = helper.getStaticCredentials("myprofile").shouldNotBeNull()
            creds.accessKeyId shouldBe "AKIAIOSFODNN7EXAMPLE"
            creds.secretAccessKey shouldBe "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
            creds.sessionToken.shouldBeNull()

            Files.deleteIfExists(credentialsFile)
        }

        test("removeStaticCredentials removes only static credential keys from profile") {
            val credentialsFile = Files.createTempFile("aws-credentials", ".ini")
            credentialsFile.writeText(
                """
                [default]
                aws_access_key_id = AKIAIOSFODNN7EXAMPLE
                aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
                region = us-east-1

                [other-profile]
                aws_access_key_id = AKIAIOSFODNN7OTHER
                aws_secret_access_key = otherSecretKey
                """
                    .trimIndent()
            )

            val helper = AwsCredentialsFileHelper(credentialsFile)
            helper.removeStaticCredentials("default").shouldBeTrue()

            val content = credentialsFile.toFile().readText()
            content shouldBe
                """
                    [default]
                    region = us-east-1

                    [other-profile]
                    aws_access_key_id = AKIAIOSFODNN7OTHER
                    aws_secret_access_key = otherSecretKey
                """
                    .trimIndent()

            helper.hasStaticCredentials("default").shouldBeFalse()
            helper.hasStaticCredentials("other-profile").shouldBeTrue()

            Files.deleteIfExists(credentialsFile)
        }

        test("removeStaticCredentials removes session token along with credentials") {
            val credentialsFile = Files.createTempFile("aws-credentials", ".ini")
            credentialsFile.writeText(
                """
                [default]
                aws_access_key_id = AKIAIOSFODNN7EXAMPLE
                aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
                aws_session_token = FwoGZXIvYXdzEBYaDH...
                """
                    .trimIndent()
            )

            val helper = AwsCredentialsFileHelper(credentialsFile)
            helper.removeStaticCredentials("default").shouldBeTrue()

            val content = credentialsFile.toFile().readText()
            content shouldBe "[default]"

            Files.deleteIfExists(credentialsFile)
        }

        test("removeStaticCredentials returns false when profile has no static credentials") {
            val credentialsFile = Files.createTempFile("aws-credentials", ".ini")
            credentialsFile.writeText(
                """
                [default]
                region = us-east-1
                """
                    .trimIndent()
            )

            val helper = AwsCredentialsFileHelper(credentialsFile)
            helper.removeStaticCredentials("default").shouldBeFalse()

            Files.deleteIfExists(credentialsFile)
        }

        test("removeStaticCredentials returns false when file does not exist") {
            val credentialsFile = Files.createTempFile("aws-credentials", ".ini")
            Files.deleteIfExists(credentialsFile)

            val helper = AwsCredentialsFileHelper(credentialsFile)
            helper.removeStaticCredentials("default").shouldBeFalse()
        }
    }
}
