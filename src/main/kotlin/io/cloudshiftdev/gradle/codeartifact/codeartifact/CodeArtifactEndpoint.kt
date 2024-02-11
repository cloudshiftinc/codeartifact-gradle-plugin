package io.cloudshiftdev.gradle.codeartifact.codeartifact

import com.fasterxml.jackson.annotation.JsonIgnore
import java.net.URI

internal data class CodeArtifactEndpoint(
    val domain: String,
    val domainOwner: String,
    val region: String,
    val repository: String,
    val url: URI
) {
  @get:JsonIgnore
  val cacheKey: String
    get() = url.toString()

  companion object {
    fun fromUrl(url: URI): CodeArtifactEndpoint? {
      val urlString = url.toString().removeSuffix("/")
      val match = regex.matchEntire(urlString) ?: return null
      return CodeArtifactEndpoint(
          match.groups["domain"]!!.value,
          match.groups["domainOwner"]!!.value,
          match.groups["region"]!!.value,
          match.groups["repository"]!!.value,
          URI(urlString))
    }

    private val regex =
        """^https://(?<domain>.*?)-(?<domainOwner>.*?).d.codeartifact.(?<region>.+?).amazonaws.com/maven/(?<repository>.+?)(?:/|\?.*|/\?.*)?$"""
            .toRegex()
  }
}
