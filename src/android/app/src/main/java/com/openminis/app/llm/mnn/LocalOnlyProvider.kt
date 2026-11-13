package com.openminis.app.llm.mnn

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 端侧助理模式的**占位 provider**。
 *
 * ## 为什么需要它
 *
 * agent loop 的签名里 provider 是非空的，一路上还有一堆
 * `provider.model` / `provider.name` 的记账（上下文计费、回退链、
 * 标题生成的最后一档…）。而助理模式的这一轮**根本不发云端请求**：
 * 工具调用是端侧决定的，跑完就 `break`。
 *
 * 所以这里给 loop 一个「长得像 provider、但永远不会发请求」的东西：
 * 真到了要发请求的那一步，它直接抛错 —— 那说明流程走错了
 * （端侧计划没生效却还想问云端），必须炸出来，不能静默返回空。
 *
 * ## 用法
 *
 * 只有「没配云端模型 + 这句话端侧能办」时才用它。配了模型时，
 * 照旧用真的 provider，端侧计划失败还能回退到云端。
 */
object LocalOnlyProvider : LLMProvider {

    override val name: String = "端侧助理"

    override var model: LLMModel = LLMModel(
        id = "local-assistant",
        displayName = "端侧助理",
        provider = "local",
        contextWindow = 8_192,
        maxOutputTokens = 1_024,
    )

    private fun noCloud(): Nothing =
        throw IllegalStateException(
            "端侧助理模式没有云端模型：这句话应该由端侧工具调用处理，不该发云端请求",
        )

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = noCloud()

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = flow { noCloud() }
}
