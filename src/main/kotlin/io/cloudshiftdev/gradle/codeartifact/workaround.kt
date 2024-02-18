package io.cloudshiftdev.gradle.codeartifact

import aws.smithy.kotlin.runtime.auth.awssigning.AwsSigningAttributes
import aws.smithy.kotlin.runtime.auth.awssigning.HashSpecification
import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.http.interceptors.HttpInterceptor
import aws.smithy.kotlin.runtime.http.request.HttpRequest

// workaround for https://github.com/awslabs/aws-sdk-kotlin/issues/1217; remove once issue addressed
internal class PrecomputedHashInterceptor(private val hash: String) : HttpInterceptor {
    override suspend fun modifyBeforeRetryLoop(
        context: ProtocolRequestInterceptorContext<Any, HttpRequest>
    ): HttpRequest {
        context.executionContext[AwsSigningAttributes.HashSpecification] =
            HashSpecification.Precalculated(hash)
        return context.protocolRequest
    }
}
