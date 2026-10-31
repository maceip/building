@file:OptIn(io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.pureswift.swiftui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.cupertino.*
import io.github.alexzhirkevich.cupertino.section.*
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.serialization.json.*

private val LocalCupertinoSectionScope = compositionLocalOf<SectionScope?> { null }
private fun sectionStyle(node: ViewNode) = SectionStyle.entries.firstOrNull { it.name == node.string("style") } ?: SectionStyle.InsetGrouped

@Composable
private fun sectionState(node: ViewNode): SectionState {
    val collapsed = node.bool("collapsed") ?: false
    val canCollapse = (node.bool("canCollapse") ?: true) && cupertinoEnabled(node)
    val state = rememberSectionState(collapsed, canCollapse)
    val currentNode by rememberUpdatedState(node)
    LaunchedEffect(state, collapsed, canCollapse) {
        state.canCollapse = canCollapse
        if (collapsed != state.isCollapsed) { if (collapsed) state.collapse() else state.expand() }
    }
    LaunchedEffect(state, node.long("commandID")) {
        when (node.string("command")) { "collapse" -> state.collapse(); "expand" -> state.expand(); "toggle" -> state.toggle() }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isCollapsed }.distinctUntilChanged().drop(1).collect {
            val current = currentNode
            if (it != (current.bool("collapsed") ?: false)) Props(current.props).boolAction("onCollapsedChange")?.invoke(it)
        }
    }
    return state
}

@Composable
private fun sectionTitle(node: ViewNode) {
    if (cupertinoHasSlot(node, "title")) cupertinoSlot(node, "title")
    else if (node.string("title") != null) CupertinoText(node.string("title")!!)
    else cupertinoSlot(node)
}

@Composable
internal fun RenderCupertinoSections(node: ViewNode): Boolean {
    when (val name = node.string("name")) {
        "Modifier.sectionContainerBackground" -> Box(node.composeModifiers().sectionContainerBackground(sectionStyle(node))) { cupertinoSlot(node) }
        "ProvideSectionStyle" -> ProvideSectionStyle(sectionStyle(node)) { cupertinoSlot(node) }
        "CupertinoSection" -> {
            val style = sectionStyle(node)
            CupertinoSection(
                modifier = node.composeModifiers(), style = style, state = sectionState(node),
                shape = cupertinoShape(node, "shape", CupertinoSectionDefaults.shape(style)) as? CornerBasedShape ?: CupertinoSectionDefaults.shape(style),
                color = cupertinoColor(node, "color") ?: if (style.grouped) CupertinoSectionDefaults.Color else Color.Transparent,
                dividerPadding = cupertinoPadding(node, "dividerPadding", PaddingValues(start = CupertinoSectionDefaults.DividerPadding)),
                contentPadding = cupertinoPadding(node, "contentPadding", CupertinoSectionDefaults.paddingValues(style, true)),
                title = if (cupertinoHasSlot(node, "title")) ({ cupertinoSlot(node, "title") }) else null,
                caption = if (cupertinoHasSlot(node, "caption")) ({ cupertinoSlot(node, "caption") }) else null
            ) { CompositionLocalProvider(LocalCupertinoSectionScope provides this) { cupertinoSlot(node) } }
        }
        "CupertinoLazyColumn", "LazyListScope.section", "LazyListScope.stickySection" -> LazySections(node)
        "CupertinoSectionDefaults.LabelChevron" -> CupertinoSectionDefaults.LabelChevron()
        "CupertinoSectionDefaults.TextFieldClearButton" -> {
            val enabled = cupertinoEnabled(node)
            CupertinoSectionDefaults.TextFieldClearButton(node.bool("visible") ?: true) { if (enabled) Props(node.props).voidAction("onClick")?.invoke() }
        }
        "CupertinoSectionDefaults.PickerButton" -> CupertinoSectionDefaults.PickerButton(
            modifier = node.composeModifiers(), expanded = node.bool("expanded") ?: false,
            shape = cupertinoShape(node, "shape", CupertinoTheme.shapes.small),
            containerColor = cupertinoColor(node, "containerColor") ?: CupertinoTheme.colorScheme.quaternarySystemFill,
            activeContentColor = cupertinoColor(node, "activeContentColor") ?: CupertinoTheme.colorScheme.accent,
            contentColor = cupertinoColor(node, "contentColor") ?: CupertinoTheme.colorScheme.label,
            title = { sectionTitle(node) })
        else -> {
            if (name?.startsWith("LazySectionScope.") == true) {
                CupertinoText("This section is unavailable.")
                return true
            }
            if (name?.startsWith("SectionScope.") != true) return false
            val scope = LocalCupertinoSectionScope.current
            if (scope == null) CupertinoText("This row is unavailable.") else with(scope) { SectionRow(node) }
        }
    }
    return true
}

@Composable
private fun SectionScope.SectionRow(node: ViewNode) {
    val p = Props(node.props)
    val enabled = cupertinoEnabled(node)
    when (node.string("name")) {
        "SectionScope.SectionItem" -> SectionItem(node.composeModifiers(),
            paddingValues = cupertinoPadding(node, "paddingValues", CupertinoSectionDefaults.PaddingValues),
            leadingContent = { cupertinoSlot(node, "leadingContent") }, trailingContent = { cupertinoSlot(node, "trailingContent") }, title = { sectionTitle(node) })
        "SectionScope.SectionLink" -> SectionLink(
            onClick = { if (enabled) p.voidAction("onClick")?.invoke() }, modifier = node.composeModifiers(), enabled = enabled,
            onClickLabel = node.string("onClickLabel"), icon = { cupertinoSlot(node, "icon") }, caption = { cupertinoSlot(node, "caption") },
            chevron = { if (cupertinoHasSlot(node, "chevron")) cupertinoSlot(node, "chevron") else CupertinoSectionDefaults.LabelChevron() }, title = { sectionTitle(node) })
        "SectionScope.SectionDropdownMenu" -> SectionDropdownMenu(
            onClick = { if (enabled) p.voidAction("onClick")?.invoke() }, modifier = node.composeModifiers(), enabled = enabled,
            icon = { cupertinoSlot(node, "icon") }, onClickLabel = node.string("onClickLabel"), selectedLabel = { cupertinoSlot(node, "selectedLabel") },
            title = { sectionTitle(node) }, menu = { padding -> Box(Modifier.padding(padding)) { cupertinoSlot(node, "menu") } })
        "SectionScope.SectionDatePicker" -> {
            val state = cupertinoDateState(node)
            SectionDatePicker(state = state, expanded = node.bool("expanded") ?: false,
                onExpandedChange = { if (enabled) p.boolAction("onExpandedChange")?.invoke(it) }, modifier = node.composeModifiers(), enabled = enabled,
                leadingContent = { cupertinoSlot(node, "leadingContent") }, buttonColor = cupertinoColor(node, "buttonColor") ?: Color.Unspecified,
                button = { buttonModifier, titleModifier, label ->
                    if (cupertinoHasSlot(node, "button")) Box(buttonModifier) { cupertinoSlot(node, "button") }
                    else CupertinoSectionDefaults.PickerButton(modifier = buttonModifier, expanded = node.bool("expanded") ?: false,
                        containerColor = cupertinoColor(node, "buttonColor") ?: Color.Unspecified,
                        title = { CupertinoText(label, modifier = titleModifier) })
                },
                picker = { CupertinoPickerInputGate(enabled) { if (cupertinoHasSlot(node, "picker")) cupertinoSlot(node, "picker") else CupertinoDatePicker(state, style = cupertinoDateStyle(node)) } },
                title = { sectionTitle(node) })
        }
        "SectionScope.SectionTimePicker" -> {
            val state = cupertinoTimeState(node)
            SectionTimePicker(state = state, expanded = node.bool("expanded") ?: false,
                onExpandedChange = { if (enabled) p.boolAction("onExpandedChange")?.invoke(it) }, modifier = node.composeModifiers(), enabled = enabled,
                leadingContent = { cupertinoSlot(node, "leadingContent") }, buttonColor = cupertinoColor(node, "buttonColor") ?: Color.Unspecified,
                button = { buttonModifier, titleModifier, label ->
                    if (cupertinoHasSlot(node, "button")) Box(buttonModifier) { cupertinoSlot(node, "button") }
                    else CupertinoSectionDefaults.PickerButton(modifier = buttonModifier, expanded = node.bool("expanded") ?: false,
                        containerColor = cupertinoColor(node, "buttonColor") ?: Color.Unspecified,
                        title = { CupertinoText(label, modifier = titleModifier) })
                },
                picker = { CupertinoPickerInputGate(enabled) { if (cupertinoHasSlot(node, "picker")) cupertinoSlot(node, "picker") else CupertinoTimePicker(state) } }, title = { sectionTitle(node) })
        }
        "SectionScope.SectionTextField" -> {
            val input = cupertinoStringInputState(node)
            val change: (String) -> Unit = { cupertinoChangeString(node, input, enabled, it) }
            SectionTextField(
            value = input.value.text, onValueChange = change,
            modifier = node.composeModifiers(), enabled = enabled, readOnly = node.bool("readOnly") ?: false,
            textStyle = cupertinoTextStyle(node, "textStyle", CupertinoTheme.typography.body),
            placeholder = if (cupertinoHasSlot(node, "placeholder")) ({ cupertinoSlot(node, "placeholder") }) else null,
            singleLine = node.bool("singleLine") ?: false, maxLines = if (node.bool("singleLine") == true) 1 else (node.long("maxLines") ?: Int.MAX_VALUE.toLong()).toInt().coerceAtLeast((node.long("minLines") ?: 1L).toInt().coerceAtLeast(1)),
            minLines = if (node.bool("singleLine") == true) 1 else (node.long("minLines") ?: 1L).toInt().coerceAtLeast(1),
            visualTransformation = cupertinoVisualTransformation(node), keyboardOptions = cupertinoKeyboardOptions(node),
            keyboardActions = cupertinoKeyboardActions(node, enabled), colors = textEntryColors(node, "plain"),
            trailingIcon = { interaction ->
                if (cupertinoHasSlot(node, "trailingIcon")) cupertinoSlot(node, "trailingIcon")
                else { val focused by interaction.collectIsFocusedAsState()
                    CupertinoSectionDefaults.TextFieldClearButton(focused && input.value.text.isNotEmpty()) { change("") }
                }
            })
        }
        else -> CupertinoText("This row is unavailable.")
    }
}

private data class LazyRow(val node: ViewNode, val modifier: Modifier, val enabled: Boolean,
    val textStyle: androidx.compose.ui.text.TextStyle, val textColors: CupertinoTextFieldColors,
    val date: CupertinoDatePickerState?, val time: CupertinoTimePickerState?, val color: Color, val dateStyle: DatePickerStyle,
    val textInput: CupertinoTextInputState?)
private data class LazySection(val node: ViewNode, val state: SectionState, val color: Color, val rows: List<LazyRow>)

@Composable
private fun LazySections(node: ViewNode) {
    val sections = if (node.string("name") == "CupertinoLazyColumn") cupertinoSlotNodes(node, "content") else listOf(node)
    val prepared = sections.map { section -> key(section.id) {
        val rows = cupertinoSlotNodes(section, "content").map { row -> key(row.id) {
            LazyRow(row, row.composeModifiers(), cupertinoEnabled(row),
                cupertinoTextStyle(row, "textStyle", CupertinoTheme.typography.body), textEntryColors(row, "plain"),
                if (row.string("name") == "LazySectionScope.datePicker") cupertinoDateState(row) else null,
                if (row.string("name") == "LazySectionScope.timePicker") cupertinoTimeState(row) else null,
                cupertinoColor(row, "buttonColor") ?: Color.Unspecified, cupertinoDateStyle(row),
                if (row.string("name") == "LazySectionScope.textField") cupertinoStringInputState(row) else null)
        } }
        LazySection(section, sectionState(section), cupertinoColor(section, "color") ?: Color.Unspecified, rows)
    } }
    LazyColumn(modifier = node.composeModifiers(), contentPadding = cupertinoPadding(node, "contentPadding", PaddingValues(0.dp))) {
        for (section in prepared) {
            val s = section.node
            val body: LazySectionScope.() -> Unit = { for (row in section.rows) lazyRow(row) }
            if (s.string("name") == "LazyListScope.stickySection") stickySection(
                style = sectionStyle(s), state = section.state, color = section.color,
                title = if (cupertinoHasSlot(s, "title")) ({ padding -> Box(Modifier.padding(padding)) { cupertinoSlot(s, "title") } }) else null,
                caption = if (cupertinoHasSlot(s, "caption")) ({ cupertinoSlot(s, "caption") }) else null, content = body)
            else this.section(style = sectionStyle(s), state = section.state, color = section.color,
                title = if (cupertinoHasSlot(s, "title")) ({ cupertinoSlot(s, "title") }) else null,
                caption = if (cupertinoHasSlot(s, "caption")) ({ cupertinoSlot(s, "caption") }) else null, content = body)
        }
    }
}

private fun LazySectionScope.lazyRow(row: LazyRow) {
    val n = row.node; val p = Props(n.props); val enabled = row.enabled
    val itemKey = n.string("key") ?: n.id
    val icon: (@Composable () -> Unit)? = if (cupertinoHasSlot(n, "icon")) ({ cupertinoSlot(n, "icon") }) else null
    val title: @Composable () -> Unit = { sectionTitle(n) }
    when (n.string("name")) {
        "LazySectionScope.link" -> link(onClick = { if (enabled) p.voidAction("onClick")?.invoke() }, key = itemKey, enabled = enabled,
            icon = icon, onClickLabel = n.string("onClickLabel"), caption = { cupertinoSlot(n, "caption") }, title = title)
        "LazySectionScope.switch" -> switch(checked = n.bool("checked") ?: false, onCheckedChange = { if (enabled) p.boolAction("onCheckedChange")?.invoke(it) },
            modifier = row.modifier, key = itemKey, enabled = enabled, icon = icon,
            thumbContent = if (cupertinoHasSlot(n, "thumbContent")) ({ cupertinoSlot(n, "thumbContent") }) else null, title = title)
        "LazySectionScope.dropdownMenu" -> dropdownMenu(expanded = n.bool("expanded") ?: false,
            onDismissRequest = { p.voidAction("onDismissRequest")?.invoke() }, onClick = { if (enabled) p.voidAction("onClick")?.invoke() },
            modifier = row.modifier, key = itemKey, enabled = enabled, icon = icon,
            width = (n.double("width") ?: 250.0).dp, selectedLabel = { cupertinoSlot(n, "selectedLabel") }, title = title,
            content = { RenderCupertinoMenuChildren(this, n) })
        "LazySectionScope.datePicker" -> datePicker(state = row.date!!, expanded = n.bool("expanded") ?: false,
            onExpandedChange = { if (enabled) p.boolAction("onExpandedChange")?.invoke(it) }, modifier = row.modifier,
            style = row.dateStyle, enabled = enabled, icon = icon, buttonColor = row.color, title = title)
        "LazySectionScope.timePicker" -> timePicker(state = row.time!!, expanded = n.bool("expanded") ?: false,
            onExpandedChange = { if (enabled) p.boolAction("onExpandedChange")?.invoke(it) }, modifier = row.modifier,
            enabled = enabled, icon = icon, buttonColor = row.color, title = title)
        "LazySectionScope.textField" -> {
            val input = row.textInput!!
            val change: (String) -> Unit = { cupertinoChangeString(n, input, enabled, it) }
            textField(value = input.value.text, onValueChange = change,
            modifier = row.modifier, enabled = enabled, readOnly = n.bool("readOnly") ?: false,
            placeholder = if (cupertinoHasSlot(n, "placeholder")) ({ cupertinoSlot(n, "placeholder") }) else null,
            singleLine = n.bool("singleLine") ?: false,
            textStyle = row.textStyle, colors = row.textColors,
            visualTransformation = cupertinoVisualTransformation(n), keyboardOptions = cupertinoKeyboardOptions(n),
            keyboardActions = cupertinoKeyboardActions(n, enabled),
            minLines = if (n.bool("singleLine") == true) 1 else (n.long("minLines") ?: 1L).toInt().coerceAtLeast(1),
            maxLines = if (n.bool("singleLine") == true) 1 else (n.long("maxLines") ?: Int.MAX_VALUE.toLong()).toInt().coerceAtLeast((n.long("minLines") ?: 1L).toInt().coerceAtLeast(1)),
            trailingIcon = { interaction ->
                if (cupertinoHasSlot(n, "trailingIcon")) cupertinoSlot(n, "trailingIcon")
                else { val focused by interaction.collectIsFocusedAsState()
                    CupertinoSectionDefaults.TextFieldClearButton(focused && input.value.text.isNotEmpty()) { change("") }
                }
            })
        }
        "LazySectionScope.items" -> {
            val provider = n.long("itemProvider") ?: n.itemProviderId
            val count = n.count ?: n.long("count")?.toInt() ?: 0
            val keys = cupertinoArray(n, "keys")?.map { (it as? JsonPrimitive)?.content ?: it.toString() } ?: emptyList()
            if (provider != null) repeat(count.coerceAtLeast(0)) { index ->
                item(key = "$itemKey/${keys.getOrNull(index) ?: index}", contentType = n.string("contentType")) { padding ->
                    val child = remember(provider, n.long("contentVersion"), index) { SwiftBridge.sink.itemNode(provider, index) }
                    Box(Modifier.padding(padding)) { child?.let { RenderChild(it) } }
                }
            } else for (child in cupertinoSlotNodes(n, "content")) item(key = "$itemKey/${child.id}") { padding -> Box(Modifier.padding(padding)) { RenderChild(child) } }
        }
        else -> item(key = itemKey, contentType = n.string("contentType"), dividerPadding = (n.double("dividerPadding") ?: 16.0).dp) { padding ->
            Box(row.modifier.padding(padding)) { if (n.string("name") == "LazySectionScope.item") cupertinoSlot(n) else RenderChild(n) }
        }
    }
}
