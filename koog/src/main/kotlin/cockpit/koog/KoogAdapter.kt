package cockpit.koog

import cockpit.*
import java.io.File
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toStreamFrames
import ai.koog.serialization.typeToken
import ai.koog.agents.snapshot.feature.Persistence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class KoogAdapter : RuntimeAdapter {
    override val name = "Koog 1.3.0"
    override suspend fun run(calls: List<Call>, device: Device, storage: File): String {
        val agent = createAgent(calls, device, storage)
        return try { agent.run("开启下班模式", sessionId = "cockpit") } finally { agent.close() }
    }

    private fun createAgent(calls: List<Call>, device: Device, directory: File, failAfterFirstResult: Boolean = false): ai.koog.agents.core.agent.GraphAIAgent<String, String> {
        directory.mkdirs()
        val tool = object : SimpleTool<Call>(typeToken<Call>(), "cockpit_invoke", "Execute a bounded mock cockpit action") {
            override suspend fun execute(args: Call): String = Json.encodeToString(device.invoke(args))
        }
        return AIAgent(
            promptExecutor = ScriptedExecutor(calls, failAfterFirstResult),
            llmModel = OpenAIModels.Chat.GPT4o,
            systemPrompt = "Deterministic integration verification. Only simulated tools are registered.",
            toolRegistry = ToolRegistry { tool(tool) },
        ) {
            install(Persistence) {
                storage = AndroidCompatibleCheckpoints(directory)
                enableAutomaticPersistence = true
            }
        }
    }

    override suspend fun verifyNativeRecovery(device: Device, storage: File) {
        val calls = listOf(Call("persisted-media", "media"))
        val first = createAgent(calls, device, storage, failAfterFirstResult = true)
        try {
            val failure = runCatching { first.run("开启下班模式", sessionId = "native-recovery") }.exceptionOrNull()
            check(failure != null) { "expected injected model disconnection" }
        } finally { first.close() }
        check(device.effects == listOf("media"))
        check(File(storage, "checkpoints").walkTopDown().any { it.isFile }) { "no native checkpoint written" }
        val second = createAgent(calls, device, storage)
        try {
            val answer = second.run("开启下班模式", sessionId = "native-recovery")
            check("succeeded" in answer) { "restored prompt lost tool receipt" }
        } finally { second.close() }
        check(device.effects == listOf("media")) { "native recovery replayed effect" }
    }
}

/** Replaces only the model. Koog's actual loop, serialization and tool dispatch remain in use. */
private class ScriptedExecutor(private val calls: List<Call>, private val failAfterFirstResult: Boolean = false) : PromptExecutor() {
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val receipts = prompt.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().map { it.output }
        if (failAfterFirstResult && receipts.isNotEmpty()) error("INJECTED_MODEL_DISCONNECTION")
        val index = receipts.size
        if (index < calls.size) {
            check(tools.any { it.name == "cockpit_invoke" })
            val call = calls[index]
            return Message.Assistant(
                listOf(MessagePart.Tool.Call("proposal-${index + 1}", "cockpit_invoke", Json.encodeToString(call))),
                ResponseMetaInfo.Empty,
            )
        }
        check(receipts.isNotEmpty() || calls.isEmpty()) { "framework did not feed back tool receipts" }
        return Message.Assistant(receipts.joinToString("\n"), ResponseMetaInfo.Empty)
    }
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
        execute(prompt, model, tools).toStreamFrames().forEach { emit(it) }
    }
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("unused")
    override fun close() {}
}
