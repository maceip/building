@file:OptIn(io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi::class)
package com.pureswift.swiftui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.cupertino.*
import io.github.alexzhirkevich.cupertino.theme.*
import kotlinx.serialization.json.*

internal val LocalCupertinoTextInteraction = staticCompositionLocalOf<MutableInteractionSource?> { null }
private val LocalCupertinoSearchState = staticCompositionLocalOf<CupertinoSearchTextFieldState?> { null }

internal fun cupertinoEditingValue(value: JsonObject): TextFieldValue {
    val text = value.string("text") ?: ""
    fun index(key: String, fallback: Int): Int = (value.double(key)?.toInt() ?: fallback).coerceIn(0, text.length)
    val start = index("selectionStart", text.length)
    val end = index("selectionEnd", start)
    val composition = if (value["compositionStart"] is JsonPrimitive && value["compositionEnd"] is JsonPrimitive &&
        value.double("compositionStart") != null && value.double("compositionEnd") != null) {
        TextRange(index("compositionStart", 0), index("compositionEnd", 0))
    } else null
    return TextFieldValue(text, TextRange(start, end), composition)
}

internal fun cupertinoEditingJSON(value: TextFieldValue): String = buildJsonObject {
    put("text", value.text); put("selectionStart", value.selection.start); put("selectionEnd", value.selection.end)
    value.composition?.let { put("compositionStart", it.start); put("compositionEnd", it.end) }
}.toString()

/**
 * Native editing happens before Swift's scheduled render reaches this field.
 * An acknowledged earlier edit must not replace a newer local edit. Matching
 * by the wire value also preserves selection/composition for String bindings,
 * which cannot carry those details back from Swift.
 */
internal class CupertinoTextInputState(initial: TextFieldValue, private val editingMode: Boolean) {
    var value by mutableStateOf(initial)
        private set
    private var received = initial
    private val pending = mutableListOf<TextFieldValue>()

    private fun matches(left: TextFieldValue, right: TextFieldValue): Boolean =
        if (editingMode) left == right else left.text == right.text

    fun edit(next: TextFieldValue, sentToSwift: Boolean) {
        value = next
        if (sentToSwift && (pending.isEmpty() || !matches(pending.last(), next))) pending += next
    }

    fun receive(external: TextFieldValue) {
        // Unchanged parent state is not a new instruction to reset the editor.
        if (matches(received, external)) return
        received = external
        val acknowledged = pending.indexOfFirst { matches(it, external) }
        if (acknowledged >= 0) {
            // Swift may coalesce multiple callbacks into this render. Its main
            // looper delivers renders in order; discard all edits through the
            // acknowledged one, retaining any newer native edit and its cursor.
            pending.subList(0, acknowledged + 1).clear()
            return
        }
        pending.clear()
        value = if (editingMode) external else TextFieldValue(external.text, TextRange(external.text.length))
    }
}

@Composable
internal fun cupertinoStringInputState(node: ViewNode): CupertinoTextInputState {
    val external = TextFieldValue(node.string("value") ?: "")
    val state = remember(node.id) { CupertinoTextInputState(external, editingMode = false) }
    state.receive(external)
    return state
}

internal fun cupertinoChangeString(node: ViewNode, state: CupertinoTextInputState, enabled: Boolean, next: String) {
    if (!enabled || node.bool("readOnly") == true) return
    val callback = Props(node.props).stringAction("onValueChange")
    state.edit(TextFieldValue(next, TextRange(next.length)), sentToSwift = callback != null)
    callback?.invoke(next)
}

@Composable
internal fun textEntryColors(node: ViewNode, kind: String): CupertinoTextFieldColors {
    val source = cupertinoObject(node, "colors")?.let { node.copy(props = it) } ?: node
    val scheme = CupertinoTheme.colorScheme
    val text = cupertinoColor(source, "focusedTextColor") ?: scheme.label
    val errorText = cupertinoColor(source, "errorTextColor") ?: CupertinoColors.systemRed
    val container = cupertinoColor(source, "focusedContainerColor") ?: when (kind) {
        "search" -> if (scheme.isDark) scheme.tertiarySystemFill else scheme.quaternarySystemFill
        "bordered" -> scheme.secondarySystemGroupedBackground
        else -> Color.Transparent
    }
    val cursor = cupertinoColor(source, "cursorColor") ?: scheme.accent
    val border = cupertinoColor(source, "focusedBorderColor") ?: if (kind == "bordered") scheme.separator else Color.Transparent
    val icon = cupertinoColor(source, "focusedLeadingIconColor") ?: scheme.secondaryLabel
    val trailing = cupertinoColor(source, "focusedTrailingIconColor") ?: icon
    val placeholder = cupertinoColor(source, "focusedPlaceholderColor") ?: scheme.secondaryLabel
    return CupertinoTextFieldDefaults.colors(
        focusedTextColor = text, unfocusedTextColor = cupertinoColor(source, "unfocusedTextColor") ?: scheme.label,
        disabledTextColor = cupertinoColor(source, "disabledTextColor") ?: scheme.secondaryLabel,
        errorTextColor = errorText, focusedContainerColor = container,
        unfocusedContainerColor = cupertinoColor(source, "unfocusedContainerColor") ?: container,
        disabledContainerColor = cupertinoColor(source, "disabledContainerColor") ?: container,
        errorContainerColor = cupertinoColor(source, "errorContainerColor") ?: container,
        cursorColor = cursor, errorCursorColor = cupertinoColor(source, "errorCursorColor") ?: errorText,
        selectionColors = TextSelectionColors(cupertinoColor(source, "selectionHandleColor") ?: cursor,
            cupertinoColor(source, "selectionBackgroundColor") ?: cursor.copy(alpha = .25f)),
        focusedBorderColor = border, unfocusedBorderColor = cupertinoColor(source, "unfocusedBorderColor") ?: border,
        disabledBorderColor = cupertinoColor(source, "disabledBorderColor") ?: border,
        errorBorderColor = cupertinoColor(source, "errorBorderColor") ?: if (kind == "plain") Color.Transparent else errorText,
        focusedLeadingIconColor = icon, unfocusedLeadingIconColor = cupertinoColor(source, "unfocusedLeadingIconColor") ?: icon,
        disabledLeadingIconColor = cupertinoColor(source, "disabledLeadingIconColor") ?: icon,
        errorLeadingIconColor = cupertinoColor(source, "errorLeadingIconColor") ?: icon,
        focusedTrailingIconColor = trailing, unfocusedTrailingIconColor = cupertinoColor(source, "unfocusedTrailingIconColor") ?: trailing,
        disabledTrailingIconColor = cupertinoColor(source, "disabledTrailingIconColor") ?: trailing,
        errorTrailingIconColor = cupertinoColor(source, "errorTrailingIconColor") ?: trailing,
        focusedPlaceholderColor = placeholder, unfocusedPlaceholderColor = cupertinoColor(source, "unfocusedPlaceholderColor") ?: placeholder,
        disabledPlaceholderColor = cupertinoColor(source, "disabledPlaceholderColor") ?: scheme.tertiaryLabel,
        errorPlaceholderColor = cupertinoColor(source, "errorPlaceholderColor") ?: placeholder)
}

@Composable
internal fun RenderCupertinoTextEntry(node: ViewNode): Boolean {
    val name = node.string("name") ?: return false
    val props = Props(node.props)
    val enabled = cupertinoEnabled(node)
    when (name) {
        "rememberCupertinoSearchTextFieldState" -> {
            val state = rememberCupertinoSearchTextFieldState(initiallyExpanded = node.bool("initiallyExpanded") ?: true,
                blockScrollWhenFocusedAndEmpty = node.bool("blockScrollWhenFocusedAndEmpty") ?: true,
                collapse = { !(node.bool("collapseOnlyWhenUnfocused") ?: true) || !it.isFocused })
            CompositionLocalProvider(LocalCupertinoSearchState provides state) {
                Box(node.composeModifiers().nestedScroll(state.nestedScrollConnection)) { cupertinoSlot(node, "content") }
            }
            val callback = props.stringAction("onStateChange")
            LaunchedEffect(state.isFocused, state.progress) { callback?.invoke(buildJsonObject { put("isFocused", state.isFocused); put("progress", state.progress) }.toString()) }
        }
        "CupertinoSearchTextFieldDefaults.leadingIcon" -> {
            val vector = cupertinoIcon(node.string("imageVector"))
            if (node.string("imageVector") != null && vector == null) CupertinoText("Icon unavailable.", color = Color.Red)
            else if (vector != null) CupertinoSearchTextFieldDefaults.leadingIcon(vector, node.bool("rotateWithLayoutDirection") ?: true)
            else CupertinoSearchTextFieldDefaults.leadingIcon(rotateWithLayoutDirection = node.bool("rotateWithLayoutDirection") ?: true)
        }
        "CupertinoSearchTextFieldDefaults.cancelButton" -> {
            val interaction = LocalCupertinoTextInteraction.current ?: remember(node.id) { MutableInteractionSource() }
            val callback = props.stringAction("onValueChange")
            CupertinoSearchTextFieldDefaults.cancelButton(onValueChange = { if (enabled) callback?.invoke(it) }, interactionSource = interaction,
                content = { if (cupertinoHasSlot(node, "content")) cupertinoSlot(node, "content") else CupertinoText("Cancel") })
        }
        "CupertinoTextFieldDefaults.DecorationBox" -> {
            val interaction = LocalCupertinoTextInteraction.current ?: remember(node.id) { MutableInteractionSource() }
            CompositionLocalProvider(LocalCupertinoTextInteraction provides interaction) {
                CupertinoTextFieldDefaults.DecorationBox(valueIsEmpty = node.bool("valueIsEmpty") ?: true,
                    innerTextField = { cupertinoSlot(node, "innerTextField") }, enabled = enabled,
                    contentAlignment = if (node.string("contentAlignment")?.lowercase() == "top") Alignment.Top else Alignment.CenterVertically,
                    interactionSource = interaction, textLayoutResult = null, isError = node.bool("isError") ?: false,
                    modifier = node.composeModifiers(), placeholder = optionalSlot(node, "placeholder"),
                    leadingIcon = optionalSlot(node, "leadingIcon"), trailingIcon = optionalSlot(node, "trailingIcon"),
                    colors = textEntryColors(node, "plain"))
            }
        }
        "CupertinoTextField", "CupertinoBorderedTextField", "CupertinoSearchTextField" -> RenderCupertinoInput(node)
        else -> return false
    }
    return true
}

private fun optionalSlot(node: ViewNode, name: String): (@Composable () -> Unit)? =
    if (cupertinoHasSlot(node, name)) ({ cupertinoSlot(node, name) }) else null

internal fun cupertinoKeyboardOptions(node: ViewNode, defaultIme: ImeAction = ImeAction.Default): KeyboardOptions {
    val props = Props(node.props)
    val keyboard = cupertinoObject(node, "keyboardOptions")?.let { Props(it) } ?: props
    return KeyboardOptions(
        capitalization = when (keyboard.string("capitalization")?.lowercase()) { "characters" -> KeyboardCapitalization.Characters; "words" -> KeyboardCapitalization.Words; "sentences" -> KeyboardCapitalization.Sentences; else -> KeyboardCapitalization.None },
        autoCorrect = keyboard.bool("autoCorrect") ?: true,
        keyboardType = when (keyboard.string("keyboardType")?.lowercase()) {
            "ascii" -> KeyboardType.Ascii; "number" -> KeyboardType.Number; "phone" -> KeyboardType.Phone
            "uri", "url" -> KeyboardType.Uri; "email" -> KeyboardType.Email; "password" -> KeyboardType.Password
            "numberpassword" -> KeyboardType.NumberPassword; "decimal" -> KeyboardType.Decimal; else -> KeyboardType.Text
        },
        imeAction = when (keyboard.string("imeAction")?.lowercase()) {
            "none" -> ImeAction.None; "go" -> ImeAction.Go; "next" -> ImeAction.Next; "previous" -> ImeAction.Previous
            "search" -> ImeAction.Search; "send" -> ImeAction.Send; "done" -> ImeAction.Done
            else -> defaultIme
        })
}

internal fun cupertinoKeyboardActions(node: ViewNode, enabled: Boolean): KeyboardActions {
    val props = Props(node.props)
    val anyIme = props.stringAction("onImeAction")
    fun action(key: String): (KeyboardActionScope.() -> Unit)? {
        val callback = props.voidAction(key)
        return if (callback == null && anyIme == null) null else ({ if (enabled) { callback?.invoke(); anyIme?.invoke(key.removePrefix("on").lowercase()) } })
    }
    return KeyboardActions(onDone = action("onDone"), onGo = action("onGo"), onNext = action("onNext"),
        onPrevious = action("onPrevious"), onSearch = action("onSearch"), onSend = action("onSend"))
}

internal fun cupertinoVisualTransformation(node: ViewNode): VisualTransformation =
    if (node.bool("secure") == true || node.string("visualTransformation")?.lowercase() == "password") PasswordVisualTransformation() else VisualTransformation.None

@Composable
private fun RenderCupertinoInput(node: ViewNode) {
    val props = Props(node.props)
    val name = node.string("name")
    val editingObject = cupertinoObject(node, "valueJson") ?: if (node.props["value"] is JsonObject || node.string("valueKind") == "editing") cupertinoObject(node, "value") else null
    val editingMode = editingObject != null || node.string("valueKind") == "editing"
    if (editingMode && editingObject == null) { CupertinoText("This text field is unavailable.", color = Color.Red); return }
    if (name == "CupertinoSearchTextField" && editingMode) {
        CupertinoText("Search is unavailable.", color = Color.Red)
        return
    }
    val external = editingObject?.let(::cupertinoEditingValue) ?: TextFieldValue(node.string("value") ?: "")
    val editor = remember(node.id, editingMode) { CupertinoTextInputState(external, editingMode) }
    // Reconcile before rendering, not in a deferred effect that can run after
    // another IME event with an already obsolete external value.
    editor.receive(external)
    val value = editor.value
    val enabled = cupertinoEnabled(node)
    val readOnly = node.bool("readOnly") ?: false
    val callback = props.stringAction("onValueChange")
    val onValue: (TextFieldValue) -> Unit = { next ->
        if (enabled && (!readOnly || next.text == editor.value.text)) {
            val emits = callback != null && (editingMode || (!readOnly && next.text != editor.value.text))
            editor.edit(next, sentToSwift = emits)
            if (emits) callback?.invoke(if (editingMode) cupertinoEditingJSON(next) else next.text)
        }
    }
    val onString: (String) -> Unit = { next -> if (enabled && !readOnly) {
        editor.edit(TextFieldValue(next, TextRange(next.length)), sentToSwift = callback != null)
        callback?.invoke(next)
    } }
    val keyboardOptions = cupertinoKeyboardOptions(node, if (name == "CupertinoSearchTextField") ImeAction.Search else ImeAction.Default)
    val keyboardActions = cupertinoKeyboardActions(node, enabled)
    val transformation = cupertinoVisualTransformation(node)
    val singleLine = node.bool("singleLine") ?: false
    val minimum = if (singleLine) 1 else (props.int("minLines") ?: 1).coerceAtLeast(1)
    val maximum = if (singleLine) 1 else (props.int("maxLines") ?: Int.MAX_VALUE).coerceAtLeast(minimum)
    val interaction = LocalCupertinoTextInteraction.current ?: remember(node.id) { MutableInteractionSource() }
    val placeholder = optionalSlot(node, "placeholder") ?: node.string("placeholder")?.let { text -> { CupertinoText(text) } }
    val leading = optionalSlot(node, "leadingIcon")
    val trailing = optionalSlot(node, "trailingIcon")
    val alignment = when (node.string("contentAlignment")?.lowercase()) { "top" -> Alignment.Top; "bottom" -> Alignment.Bottom; else -> Alignment.CenterVertically }
    CompositionLocalProvider(LocalCupertinoTextInteraction provides interaction) {
        when (name) {
            "CupertinoBorderedTextField" -> CupertinoBorderedTextField(value = value, onValueChange = onValue,
                modifier = node.composeModifiers(), enabled = enabled, readOnly = readOnly, textStyle = cupertinoTextStyle(node),
                placeholder = placeholder, leadingIcon = leading, trailingIcon = trailing, isError = node.bool("isError") ?: false,
                visualTransformation = transformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                singleLine = singleLine, minLines = minimum, maxLines = maximum, interactionSource = interaction,
                shape = cupertinoShape(node, default = CupertinoBorderedTextFieldDefaults.shape),
                strokeWidth = props.float("strokeWidth")?.dp ?: CupertinoBorderedTextFieldDefaults.StrokeWidth,
                paddingValues = cupertinoPadding(node, "paddingValues", CupertinoBorderedTextFieldDefaults.PaddingValues),
                contentAlignment = alignment, colors = textEntryColors(node, "bordered"))
            "CupertinoSearchTextField" -> {
                val state = LocalCupertinoSearchState.current ?: rememberCupertinoSearchTextFieldState(
                    initiallyExpanded = node.bool("initiallyExpanded") ?: true,
                    blockScrollWhenFocusedAndEmpty = node.bool("blockScrollWhenFocusedAndEmpty") ?: true)
                val cancel: (@Composable () -> Unit)? = when {
                    cupertinoHasSlot(node, "cancelButton") -> ({ cupertinoSlot(node, "cancelButton") })
                    node.bool("showCancelButton") == false -> null
                    else -> ({ CupertinoSearchTextFieldDefaults.cancelButton(onString, interactionSource = interaction) })
                }
                CupertinoSearchTextField(value.text, onString, state, textEntryColors(node, "search"), node.composeModifiers(),
                    paddingValues = cupertinoPadding(node, "paddingValues", CupertinoSearchTextFieldDefaults.PaddingValues),
                    shape = cupertinoShape(node, default = CupertinoSearchTextFieldDefaults.shape), enabled = enabled, readOnly = readOnly,
                    textStyle = cupertinoTextStyle(node), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                    visualTransformation = transformation, interactionSource = interaction,
                    placeholder = placeholder ?: { CupertinoText("Search") }, cancelButton = cancel,
                    leadingIcon = leading ?: { CupertinoSearchTextFieldDefaults.leadingIcon() }, trailingIcon = trailing ?: {})
            }
            else -> CupertinoTextField(value = value, onValueChange = onValue, modifier = node.composeModifiers(), enabled = enabled,
                readOnly = readOnly, textStyle = cupertinoTextStyle(node), placeholder = placeholder, leadingIcon = leading, trailingIcon = trailing,
                isError = node.bool("isError") ?: false, visualTransformation = transformation, keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions, singleLine = singleLine, minLines = minimum, maxLines = maximum,
                interactionSource = interaction, contentAlignment = alignment, colors = textEntryColors(node, "plain"))
        }
    }
}
