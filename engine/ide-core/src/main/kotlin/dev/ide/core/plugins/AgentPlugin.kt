package dev.ide.core.plugins

import dev.ide.agent.LlmEffort
import dev.ide.agent.mcp.CodeAssistMcpServer
import dev.ide.core.agent.AgentBackend
import dev.ide.platform.settings.PreferenceReader
import dev.ide.platform.settings.SETTINGS_PAGE_EP
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionEffect
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.action.UI_ACTION_EP
import dev.ide.ui.backend.UiAgentAttachment
import dev.ide.ui.backend.UiAgentAttachmentKind

/**
 * The AI coding agent, contributed as a built-in plugin (see docs/agentic-coding.md). Registers the "AI"
 * settings page (bring-your-own-key provider configuration). The chat service itself ([AgentBackend]) is a
 * concern backend wired by [IdeServicesBackend]; this plugin is non-essential so users can disable the
 * feature from the Plugins settings screen.
 */
internal class AgentPlugin : Plugin {
    override val manifest = PluginManifest(
        id = ID,
        name = "AI Agent",
        description = "An AI coding assistant: chat, read and edit files, and run tools under a permission policy.",
    )

    override fun register(reg: PluginRegistration) {
        reg.register(SETTINGS_PAGE_EP, AgentSettingsPage)
        reg.register(UI_ACTION_EP, askAboutSelection)
    }

    /**
     * "Ask AI": attaches the editor selection (or, with nothing selected, the whole file) to the next chat
     * message and opens the chat beside the editor. The selection travels as an attachment rather than pasted
     * text, so the composer stays empty for the question and the model sees the path and line range.
     */
    private val askAboutSelection = SimpleAction(
        id = "agent.askAboutSelection",
        text = "Ask AI",
        places = setOf(ActionPlaces.EDITOR, ActionPlaces.COMMAND_PALETTE),
        iconId = "sparkle",
        order = 5,
        visible = { it.activeFilePath != null && it.documentText != null },
    ) { ctx -> askAbout(ctx) }

    private fun askAbout(ctx: ActionContext): ActionResult {
        val path = ctx.activeFilePath ?: return ActionResult.NONE
        val text = ctx.documentText ?: return ActionResult.NONE
        val agent = AgentBackend.forProject(ctx.projectRoot) ?: return ActionResult.message("The AI agent is not available.")
        val start = (ctx.selectionStart ?: 0).coerceIn(0, text.length)
        val end = (ctx.selectionEnd ?: 0).coerceIn(start, text.length)
        val name = path.substringAfterLast('/')
        val attachment = if (end > start) {
            val first = lineOf(text, start)
            val last = lineOf(text, if (end > start && text[end - 1] == '\n') end - 1 else end)
            UiAgentAttachment(
                UiAgentAttachmentKind.SELECTION,
                name = if (first == last) "$name:$first" else "$name:$first-$last",
                path = path,
                text = text.substring(start, end),
                startLine = first,
                endLine = last,
            )
        } else {
            UiAgentAttachment(UiAgentAttachmentKind.FILE, name = name, path = path)
        }
        agent.attach(attachment)
        return ActionResult.effect(ActionEffect.Navigate(CHAT_TOOL_WINDOW))
    }

    /** The 1-based line holding [offset]. */
    private fun lineOf(text: String, offset: Int): Int {
        var line = 1
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') line++
        return line
    }

    companion object {
        /** The plugin id (non-essential; disablable from Settings > Plugins). [IdeServicesBackend] gates the
         *  agent service on it, and the UI hides the chat surfaces when it's off. */
        const val ID = "agent"

        /** The chat's tool-window id, as `AgentUiPlugin` registers it. */
        const val CHAT_TOOL_WINDOW = "agent.chat"
    }
}

/** The "AI" settings page. Values persist under `settings.ai.*`; [AgentBackend] reads them at send time. */
internal object AgentSettingsPage : SettingsPage {
    override val id: String = AgentBackend.AI_PAGE
    override val title: String = "AI"
    override val iconId: String = "sparkle"
    override val scope: SettingsScope = SettingsScope.APPLICATION
    override val order: Int = 90

    override fun onChanged(key: String, values: PreferenceReader) {
        // Unlike the MCP server, the FTP server starts and stops at once, in the open project.
        if (key == AgentBackend.FTP_PREF) AgentBackend.forProject(null)?.setFtpServerEnabled(values.bool(key, false))
    }

    override fun controls(): List<SettingControl> = listOf(
        SettingControl.Choice(
            key = "provider",
            title = "Provider",
            description = "Active AI provider. Switch providers and models directly in chat.",
            default = "anthropic",
            options = listOf(
                SettingControl.Choice.Option("anthropic", "Anthropic (Claude)"),
                SettingControl.Choice.Option("openai", "OpenAI"),
                SettingControl.Choice.Option("gemini", "Google Gemini"),
                SettingControl.Choice.Option("antigravity", "Antigravity"),
                SettingControl.Choice.Option("openrouter", "OpenRouter"),
                SettingControl.Choice.Option("muse", "Meta Muse"),
                SettingControl.Choice.Option("mimo", "Xiaomi MiMo"),
                SettingControl.Choice.Option("litert", "LiteRT-LM (On-Device)"),
                SettingControl.Choice.Option("gateway", "Custom gateway"),
            ),
        ),
        SettingControl.Text(
            key = "anthropicKey",
            title = "Anthropic API key",
            description = "Used when Anthropic is selected.",
            placeholder = "sk-ant-...",
        ),
        SettingControl.Text(
            key = "openaiKey",
            title = "OpenAI API key",
            description = "Used when OpenAI is selected.",
            placeholder = "sk-...",
        ),
        SettingControl.Text(
            key = "geminiKey",
            title = "Gemini API key",
            description = "Used when Google Gemini is selected.",
        ),
        SettingControl.Text(
            key = "antigravityKey",
            title = "Antigravity token / key",
            description = "OAuth token or Google AI key for Antigravity.",
            placeholder = "ya29... or 1//...",
        ),
        SettingControl.Text(
            key = "openrouterKey",
            title = "OpenRouter API key",
            description = "Used when OpenRouter is selected.",
            placeholder = "sk-or-...",
        ),
        SettingControl.Text(
            key = "museKey",
            title = "Meta Muse API key",
            description = "Used when Meta Muse is selected.",
            placeholder = "muse-...",
        ),
        SettingControl.Text(
            key = "mimoKey",
            title = "Xiaomi MiMo API key",
            description = "Used when Xiaomi MiMo is selected.",
            placeholder = "mimo-...",
        ),
        SettingControl.Text(
            key = "gatewayBaseUrl",
            title = "Gateway base URL",
            description = "OpenAI-compatible endpoint (Ollama, LM Studio, LiteLLM, vLLM).",
            placeholder = "http://localhost:11434/v1",
        ),
        SettingControl.Text(
            key = "gatewayKey",
            title = "Gateway API key",
            description = "API key for custom gateway (optional for local models).",
            placeholder = "sk-...",
        ),
        SettingControl.Text(
            key = "gatewayModel",
            title = "Gateway model",
            description = "Model identifier to request from custom gateway.",
            placeholder = "llama3.2",
        ),
        SettingControl.Text(
            key = "model",
            title = "Model override",
            description = "Optional model override. Leave blank to use provider default, or switch directly in chat.",
        ),
        SettingControl.Choice(
            key = "reasoningEffort",
            title = "Reasoning effort",
            description = "Thinking effort for reasoning models. Lower is faster; higher is best for complex coding.",
            default = AgentBackend.REASONING_EFFORT_DEFAULT,
            options = listOf(
                SettingControl.Choice.Option(AgentBackend.REASONING_EFFORT_DEFAULT, "Default"),
                SettingControl.Choice.Option(LlmEffort.NONE, "None"),
                SettingControl.Choice.Option(LlmEffort.MINIMAL, "Minimal"),
                SettingControl.Choice.Option(LlmEffort.LOW, "Low"),
                SettingControl.Choice.Option(LlmEffort.MEDIUM, "Medium"),
                SettingControl.Choice.Option(LlmEffort.HIGH, "High"),
                SettingControl.Choice.Option(LlmEffort.XHIGH, "Very high"),
                SettingControl.Choice.Option(LlmEffort.MAX, "Maximum"),
            ),
        ),
        SettingControl.Toggle(
            key = "webSearch",
            title = "Web search",
            description = "Allow the agent to search the web when supported by the provider.",
            default = true,
        ),
        SettingControl.Toggle(
            key = "mcpServer",
            title = "MCP server",
            description = "Expose agent tools over MCP on port " + AgentBackend.MCP_PORT + " (Streamable HTTP).",
            default = false,
        ),
        SettingControl.Toggle(
            key = AgentBackend.FTP_PREF,
            title = "FTP server",
            description = "Anonymous local upload inbox on port " + CodeAssistMcpServer.DEFAULT_FTP_PORT + " saving to assets/.",
            default = false,
        ),
        SettingControl.IntSlider(
            key = "maxTokens",
            title = "Max response tokens",
            description = "Maximum output tokens per response.",
            default = AgentBackend.DEFAULT_MAX_TOKENS,
            min = 1024,
            max = 32768,
            step = 1024,
            unit = "tok",
            advanced = true,
        ),
        SettingControl.IntSlider(
            key = "maxIterations",
            title = "Max tool iterations",
            description = "Maximum tool-call rounds per turn.",
            default = AgentBackend.DEFAULT_MAX_ITERATIONS,
            min = 1,
            max = 100,
            step = 1,
            advanced = true,
        ),
        SettingControl.IntSlider(
            key = "rpm",
            title = "Requests per minute",
            description = "Pace requests to stay under rate limits (0 = automatic).",
            default = 0,
            min = 0,
            max = 120,
            step = 1,
            unit = "/min",
            advanced = true,
        ),
        SettingControl.IntSlider(
            key = "tpmK",
            title = "Tokens per minute (K)",
            description = "Pace token usage to stay under rate limits in thousands of tokens (0 = automatic).",
            default = 0,
            min = 0,
            max = 4000,
            step = 10,
            unit = "K",
            advanced = true,
        ),
    )
}
