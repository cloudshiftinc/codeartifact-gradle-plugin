package io.cloudshiftdev.gradle.codeartifact

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class AwsCredentialsFileHelper(
    private val credentialsFilePath: Path = defaultCredentialsFilePath()
) {
    data class StaticCredentials(
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String?,
    )

    fun hasStaticCredentials(profileName: String): Boolean {
        return getStaticCredentials(profileName) != null
    }

    fun getStaticCredentials(profileName: String): StaticCredentials? {
        if (!credentialsFilePath.exists()) return null

        val content = credentialsFilePath.readText()
        val profileSection = findProfileSection(content, profileName) ?: return null

        val accessKeyId = extractValue(profileSection, "aws_access_key_id")
        val secretAccessKey = extractValue(profileSection, "aws_secret_access_key")

        if (accessKeyId == null || secretAccessKey == null) return null

        return StaticCredentials(
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            sessionToken = extractValue(profileSection, "aws_session_token"),
        )
    }

    fun removeStaticCredentials(profileName: String): Boolean {
        if (!credentialsFilePath.exists()) return false

        val content = credentialsFilePath.readText()
        val newContent = removeCredentialsFromProfile(content, profileName)

        if (newContent == content) return false

        credentialsFilePath.writeText(newContent)
        return true
    }

    private fun findProfileSection(content: String, profileName: String): String? {
        val lines = content.lines()
        val profileHeader = "[$profileName]"
        var inProfile = false
        val sectionLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed == profileHeader) {
                inProfile = true
                continue
            }
            if (inProfile) {
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    break
                }
                sectionLines.add(line)
            }
        }

        return if (sectionLines.isNotEmpty()) sectionLines.joinToString("\n") else null
    }

    private fun extractValue(section: String, key: String): String? {
        val regex = Regex("""^\s*${Regex.escape(key)}\s*=\s*(.+?)\s*$""", RegexOption.MULTILINE)
        return regex.find(section)?.groupValues?.get(1)
    }

    private fun removeCredentialsFromProfile(content: String, profileName: String): String {
        val lines = content.lines().toMutableList()
        val profileHeader = "[$profileName]"
        var inProfile = false
        val indicesToRemove = mutableListOf<Int>()
        val keysToRemove = setOf("aws_access_key_id", "aws_secret_access_key", "aws_session_token")

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed == profileHeader) {
                inProfile = true
                continue
            }
            if (inProfile) {
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inProfile = false
                    continue
                }
                val keyMatch = Regex("""^\s*(\S+?)\s*=.*$""").find(trimmed)
                if (keyMatch != null && keyMatch.groupValues[1] in keysToRemove) {
                    indicesToRemove.add(index)
                }
            }
        }

        indicesToRemove.reversed().forEach { lines.removeAt(it) }
        return lines.joinToString("\n")
    }

    companion object {
        private fun defaultCredentialsFilePath(): Path {
            val envPath = System.getenv("AWS_SHARED_CREDENTIALS_FILE")
            if (!envPath.isNullOrBlank()) {
                return Path(envPath)
            }
            val home = System.getProperty("user.home")
            return Path(home, ".aws", "credentials")
        }
    }
}
