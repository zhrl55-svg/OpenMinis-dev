package com.openminis.app.llm.mnn

import com.openminis.app.data.model.AgentContentPart
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
 * 端侧直连那一轮的 provider 装饰器。
 *
 * ## 它做什么
 *
 * 端侧的计划是以「预填工具调用」的形式进 agent loop 的（见
 * [com.openminis.app.scheduled.PrefilledToolCall]）：第一轮不发请求，
 * 直接把工具调用喂进去，执行完再问模型「结果是什么」。
 *
 * 助理模式不要那个「问模型」：**工具已经在本机跑完了**，再花一次网络
 * 往返换一个复读机不划算。所以这个装饰器认出「这次请求带着端侧那次
 * 工具的结果」，然后在**本机**给出一句收尾话，不发网络请求。
 *
 * 认出来的办法：出站历史里有 tool_result —— 端侧这一轮只可能有一次
 * 工具调用，所以它就是端侧那次的结果。
 *
 * ## 为什么不用「专用 provider」而是装饰器
 *
 * agent loop 里那个 2900 行的巨型方法一旦多一个参数 / 多一条分支，
 * Kotlin 生成的协程状态机就可能挤出 ART 校验不过的字节码（整类
 * 加载失败 → 打开聊天就崩）。装饰器把「端侧直连」的差异全部挡在
 * loop 外面，巨型方法一行都不用改。
 */
class LocalTurnProvider(
    private val delegate: LLMProvider,
    /** 本机给出的收尾话（工具卡里已经有真正的结果，这里只是收个尾）。 */
    private val summaryText: String,
    /** 工具自己报了错时的收尾话 —— 别把失败说成「已执行」。 */
    private val failureText: String,
) : LLMProvider {

    override val name: String
        get() = delegate.name

    override var model: LLMModel
        get() = delegate.model
        set(value) {
            delegate.model = value
        }

    /** 这次请求带的端侧工具结果（空 = 不是端侧那一轮，照旧走云端）。 */
    private fun localToolResults(messages: List<LLMMessage>): List<AgentContentPart.ToolResult> =
        messages.flatMap { message ->
            message.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
        }

    private fun closingText(messages: List<LLMMessage>): String? {
        val results = localToolResults(messages)
        if (results.isEmpty()) return null
        return if (results.any { it.isError }) failureText else summaryText
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> {
        val text = closingText(messages)
        if (text == null) {
            return delegate.streamMessageClamped(
                messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
            )
        }
        return flow {
            emit(LLMStreamChunk.Started)
            emit(LLMStreamChunk.Text(text))
            emit(LLMStreamChunk.Finished("stop"))
        }
    }

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse {
        val text = closingText(messages)
        if (text == null) {
            return delegate.sendMessageClamped(
                messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
            )
        }
        return LLMResponse(text = text, stopReason = "stop", usage = null)
    }
}
