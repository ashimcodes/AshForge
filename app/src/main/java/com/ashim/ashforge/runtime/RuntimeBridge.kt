package com.ashim.ashforge.runtime

import com.ashim.ashforge.model.ChatMessage
import com.ashim.ashforge.model.ChangeItem
import com.ashim.ashforge.model.ProviderProfile
import com.ashim.ashforge.model.ProjectKind
import com.ashim.ashforge.model.RuntimeEvent
import com.ashim.ashforge.model.ToolRequest
import kotlinx.coroutines.flow.Flow

data class RuntimeLaunchConfig(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
)

interface RuntimeBridge {
    val events: Flow<RuntimeEvent>
    suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile): String
    suspend fun respondToApproval(request: ToolRequest, approved: Boolean)
    suspend fun stopSession(sessionId: String)
    suspend fun stopActiveSession()
    suspend fun undoLastChanges(projectId: String): Boolean
    suspend fun acceptLastChanges(projectId: String)
    suspend fun loadPendingChanges(projectId: String): List<ChangeItem>
    suspend fun undoFileChange(projectId: String, path: String): Boolean
    suspend fun acceptFileChange(projectId: String, path: String): Boolean
}

object RuntimeLaunchConfigBuilder {
    fun build(profile: ProviderProfile, authToken: String? = null, localGatewayUrl: String? = null): RuntimeLaunchConfig {
        val environment = linkedMapOf("DISABLE_AUTOUPDATER" to "1")
        when (profile.kind.protocol) {
            com.ashim.ashforge.model.ProviderProtocol.CLAUDE_LOGIN -> Unit
            com.ashim.ashforge.model.ProviderProtocol.ANTHROPIC -> {
                environment["ANTHROPIC_BASE_URL"] = profile.baseUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            com.ashim.ashforge.model.ProviderProtocol.ANTHROPIC_GATEWAY -> {
                if (profile.kind == com.ashim.ashforge.model.ProviderKind.OPENCODE_FREE) {
                    require(!localGatewayUrl.isNullOrBlank()) { "The OpenCode Zen identity proxy is required for this provider" }
                    environment["ANTHROPIC_BASE_URL"] = localGatewayUrl.trimEnd('/')
                } else {
                    environment["ANTHROPIC_BASE_URL"] = profile.baseUrl.trimEnd('/')
                }
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            com.ashim.ashforge.model.ProviderProtocol.OPENROUTER -> {
                environment["ANTHROPIC_BASE_URL"] = profile.baseUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            com.ashim.ashforge.model.ProviderProtocol.OPENAI_RESPONSES,
            com.ashim.ashforge.model.ProviderProtocol.OPENAI_CHAT,
            -> {
                require(!localGatewayUrl.isNullOrBlank()) { "A local format gateway is required for this provider" }
                environment["ANTHROPIC_BASE_URL"] = localGatewayUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = "claude-sonnet-4-6"
            }
        }
        val runtimeModel = environment["ANTHROPIC_MODEL"] ?: profile.model
        if (profile.kind.protocol != com.ashim.ashforge.model.ProviderProtocol.CLAUDE_LOGIN) {
            environment["ANTHROPIC_DEFAULT_OPUS_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_SONNET_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = runtimeModel
            environment["ANTHROPIC_SMALL_MODEL"] = runtimeModel
            environment["ANTHROPIC_FAST_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_SUBAGENT_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"] = "1"
            environment["CLAUDE_CODE_DISABLE_TOKEN_COUNTING"] = "1"
            environment["DISABLE_TELEMETRY"] = "1"
            if (!authToken.isNullOrBlank()) {
                environment["ANTHROPIC_AUTH_TOKEN"] = authToken
                if (profile.kind == com.ashim.ashforge.model.ProviderKind.LLM_ROUTER) {
                    environment["ANTHROPIC_API_KEY"] = ""
                    environment["OPENROUTER_API_KEY"] = authToken
                } else {
                    environment["ANTHROPIC_API_KEY"] = authToken
                }
            }
        }
        return RuntimeLaunchConfig(
            executable = "/usr/local/bin/claude",
            arguments = listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"),
            environment = environment,
        )
    }
}
