@file:OptIn(io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi::class, io.github.alexzhirkevich.cupertino.adaptive.ExperimentalAdaptiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.pureswift.swiftui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.cupertino.*
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveDatePicker
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.serialization.json.*

internal val LocalCupertinoPickerState = staticCompositionLocalOf<CupertinoPickerState?> { null }

private fun yearRange(node: ViewNode): IntRange {
    val years = cupertinoArray(node, "yearRange")
    val first = (years?.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: node.long("firstYear")?.toInt() ?: 1900
    val last = (years?.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: node.long("lastYear")?.toInt() ?: 2100
    return first.coerceIn(1, 9998)..last.coerceIn(first.coerceIn(1, 9998), 9999)
}

@Composable
internal fun cupertinoDateStyle(node: ViewNode): DatePickerStyle {
    if (node.string("style")?.lowercase() !in setOf("pager", "calendar")) return DatePickerStyle.Wheel(
        height = node.double("height")?.dp ?: CupertinoPickerDefaults.Height, indicator = cupertinoWheelIndicator(node))
    val colorNode = cupertinoObject(node, "colors")?.let { node.copy(props = it) } ?: node
    val textNode = cupertinoObject(node, "textStyles")?.let { node.copy(props = it) } ?: node
    val c = CupertinoTheme.colorScheme
    val t = CupertinoTheme.typography
    val dayColor = cupertinoColor(colorNode, "dayContentColor") ?: c.label
    val todayColor = cupertinoColor(colorNode, "todayContentColor") ?: c.accent
    val selectedContainer = cupertinoColor(colorNode, "selectedDayContainerColor") ?: todayColor.copy(alpha = .2f)
    val dayStyle = cupertinoTextStyle(textNode, "day", t.title3)
    return DatePickerStyle.Pager(
        colors = CupertinoDatePickerDefaults.pagerColors(
            chevronsColor = cupertinoColor(colorNode, "chevronsColor") ?: c.accent,
            headlineContentColor = cupertinoColor(colorNode, "headlineContentColor") ?: c.label,
            weekdayContentColor = cupertinoColor(colorNode, "weekdayContentColor") ?: c.tertiaryLabel,
            todayContentColor = cupertinoColor(colorNode, "todayContentColor") ?: todayColor,
            dayContentColor = cupertinoColor(colorNode, "dayContentColor") ?: dayColor,
            disabledDayContentColor = cupertinoColor(colorNode, "disabledDayContentColor") ?: dayColor.copy(alpha = .38f),
            disabledSelectedDayContentColor = cupertinoColor(colorNode, "disabledSelectedDayContentColor") ?: todayColor.copy(alpha = .38f),
            selectedDayContentColor = cupertinoColor(colorNode, "selectedDayContentColor") ?: todayColor,
            selectedDayContainerColor = cupertinoColor(colorNode, "selectedDayContainerColor") ?: selectedContainer,
            disabledSelectedDayContainerColor = cupertinoColor(colorNode, "disabledSelectedDayContainerColor") ?: selectedContainer.copy(alpha = .38f),
            dayInSelectionRangeContentColor = cupertinoColor(colorNode, "dayInSelectionRangeContentColor") ?: c.label,
            dayInSelectionRangeContainerColor = cupertinoColor(colorNode, "dayInSelectionRangeContainerColor") ?: c.label),
        textStyles = CupertinoDatePickerDefaults.pagerTextStyles(
            headline = cupertinoTextStyle(textNode, "headline", t.headline), day = cupertinoTextStyle(textNode, "day", dayStyle),
            selectedDay = cupertinoTextStyle(textNode, "selectedDay", dayStyle.copy(fontWeight = FontWeight.Bold)), weekday = cupertinoTextStyle(textNode, "weekday", t.footnote.copy(fontWeight = FontWeight.SemiBold)),
            monthWheel = cupertinoTextStyle(textNode, "monthWheel", CupertinoPickerDefaults.textStyle)),
        rowSpacing = (node.double("rowSpacing") ?: 0.0).dp,
        rowMaxHeight = (node.double("rowMaxHeight") ?: 36.0).dp,
        userScrollEnabled = cupertinoEnabled(node) && (node.bool("userScrollEnabled") ?: true))
}

@Composable
internal fun cupertinoDateState(node: ViewNode): CupertinoDatePickerState {
    val selected = node.long("selectedDateMillis") ?: node.long("initialSelectedDateMillis")
        ?: java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay() * 86_400_000L
    val years = yearRange(node)
    val state = key(node.id, years) { rememberCupertinoDatePickerState(selected, years) }
    val currentNode by rememberUpdatedState(node)
    val enabled by rememberUpdatedState(cupertinoEnabled(node))
    LaunchedEffect(state, selected) { if (state.selectedDateMillis != selected) state.setSelection(selected) }
    LaunchedEffect(state) {
        snapshotFlow { state.selectedDateMillis }.distinctUntilChanged().drop(1).collect { value ->
            val current = currentNode
            if (enabled && value != (current.long("selectedDateMillis") ?: current.long("initialSelectedDateMillis")))
                cupertinoStringEvent(current, "onSelectionChange", buildJsonObject { put("selectedDateMillis", value) })
        }
    }
    return state
}

@Composable
internal fun cupertinoTimeState(node: ViewNode): CupertinoTimePickerState {
    val hour = (node.long("hour") ?: node.long("initialHour") ?: 0L).toInt().coerceIn(0, 23)
    val minute = (node.long("minute") ?: node.long("initialMinute") ?: 0L).toInt().coerceIn(0, 59)
    val is24Hour = node.bool("is24Hour") ?: true
    // Public upstream state has no mutable setter. Recreate only for external values;
    // interactions otherwise retain the wheel and scroll state across unrelated renders.
    val state = key(node.id, hour, minute, is24Hour) { rememberCupertinoTimePickerState(hour, minute, is24Hour) }
    val currentNode by rememberUpdatedState(node)
    val enabled by rememberUpdatedState(cupertinoEnabled(node))
    LaunchedEffect(state) {
        snapshotFlow { state.hour to state.minute }.distinctUntilChanged().drop(1).collect { (h, m) ->
            val current = currentNode
            if (enabled && (h != (current.long("hour") ?: current.long("initialHour") ?: 0L).toInt() ||
                m != (current.long("minute") ?: current.long("initialMinute") ?: 0L).toInt())) {
                cupertinoStringEvent(current, "onSelectionChange", buildJsonObject { put("hour", h); put("minute", m) })
            }
        }
    }
    return state
}

@Composable
internal fun RenderCupertinoPickers(node: ViewNode): Boolean {
    val name = node.string("name") ?: return false
    if (name !in setOf("CupertinoWheelPicker", "CupertinoDatePicker", "CupertinoDatePickerNative", "AdaptiveDatePicker", "CupertinoTimePicker", "CupertinoTimePickerNative", "CupertinoDateTimePicker", "CupertinoDateTimePickerNative")) return false
    CupertinoPickerInputGate(cupertinoEnabled(node)) {
    when (name) {
        "CupertinoWheelPicker" -> Wheel(node)
        "CupertinoDatePicker", "CupertinoDatePickerNative", "AdaptiveDatePicker" -> {
            val state = cupertinoDateState(node)
            val color = cupertinoColor(node, "containerColor") ?: CupertinoTheme.colorScheme.secondarySystemGroupedBackground
            when (name) {
                "CupertinoDatePickerNative" -> CupertinoDatePickerNative(state, node.composeModifiers(), cupertinoDateStyle(node), color)
                "AdaptiveDatePicker" -> {
                    val dateStyle = cupertinoDateStyle(node)
                    AdaptiveDatePicker(state, node.composeModifiers(), adaptation = {
                        cupertino { style = dateStyle; containerColor = color }
                        material {
                            showModeToggle = node.bool("showModeToggle") ?: true
                            if (cupertinoHasSlot(node, "title")) title = { cupertinoSlot(node, "title") }
                            if (cupertinoHasSlot(node, "headline")) headline = { cupertinoSlot(node, "headline") }
                        }
                    })
                }
                else -> CupertinoDatePicker(state, node.composeModifiers(), cupertinoDateStyle(node), color)
            }
        }
        "CupertinoTimePicker", "CupertinoTimePickerNative" -> {
            val state = cupertinoTimeState(node)
            val color = cupertinoColor(node, "containerColor") ?: CupertinoTheme.colorScheme.secondarySystemGroupedBackground
            if (name == "CupertinoTimePickerNative") CupertinoTimePickerNative(state, modifier = node.composeModifiers(), height = node.double("height")?.dp ?: CupertinoPickerDefaults.Height, containerColor = color)
            else CupertinoTimePicker(state, height = node.double("height")?.dp ?: CupertinoPickerDefaults.Height, indicator = cupertinoWheelIndicator(node), containerColor = color, modifier = node.composeModifiers())
        }
        "CupertinoDateTimePicker", "CupertinoDateTimePickerNative" -> DateTime(node, name.endsWith("Native"))
    }
    }
    return true
}

@Composable
private fun Wheel(node: ViewNode) {
    val items = cupertinoArray(node, "items")?.map { (it as? JsonPrimitive)?.content ?: it.toString() } ?: emptyList()
    if (items.isEmpty()) { CupertinoText("No picker items"); return }
    val selected = (node.long("selectedItem") ?: node.long("initiallySelectedItemIndex") ?: 0L).toInt().coerceIn(items.indices)
    val infinite = node.bool("infinite") ?: true
    val state = key(node.id, infinite) { rememberCupertinoPickerState(infinite, selected) }
    val currentNode by rememberUpdatedState(node)
    val enabled by rememberUpdatedState(cupertinoEnabled(node))
    var updating by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state, selected, items.size) {
        if (state.currentSelectedItem(items.size) != selected) {
            updating = true
            try { state.scrollToItem(selected) } finally { updating = false }
        }
    }
    LaunchedEffect(state, node.long("commandID")) {
        val target = (node.long("commandIndex") ?: selected.toLong()).toInt().coerceIn(items.indices)
        updating = true
        try {
            when (node.string("command")) {
                "scrollToItem" -> state.scrollToItem(target)
                "animateScrollToItem" -> state.animateScrollToItem(target)
            }
        } finally { updating = false }
    }
    LaunchedEffect(state, items.size) {
        snapshotFlow { state.currentSelectedItem(items.size) }.distinctUntilChanged().drop(1).collect { index ->
            val current = currentNode
            if (enabled && !updating && index != (current.long("selectedItem") ?: current.long("initiallySelectedItemIndex") ?: 0L).toInt())
                current.long("onSelectionChange")?.let { SwiftBridge.sink.invokeInt(it, index) }
        }
    }
    val color = cupertinoColor(node, "containerColor") ?: CupertinoTheme.colorScheme.secondarySystemGroupedBackground
    val itemKeys = cupertinoArray(node, "keys")?.map { (it as? JsonPrimitive)?.content ?: it.toString() }
    CompositionLocalProvider(LocalCupertinoPickerState provides state) {
    CupertinoWheelPicker(
        state = state, items = items.indices.toList(), height = node.double("height")?.dp ?: CupertinoPickerDefaults.Height,
        modifier = node.composeModifiers(), containerColor = color,
        indicator = cupertinoWheelIndicator(node), textStyle = cupertinoTextStyle(node, "textStyle", CupertinoPickerDefaults.textStyle),
        rotationTransformOrigin = TransformOrigin((node.double("rotationPivotX") ?: .5).toFloat(), (node.double("rotationPivotY") ?: .5).toFloat()),
        key = { itemKeys?.getOrNull(it) ?: it }, withRotation = node.bool("withRotation") ?: false,
        enabled = cupertinoEnabled(node),
        horizontalAlignment = when (node.string("horizontalAlignment")) { "leading", "start" -> Alignment.Start; "trailing", "end" -> Alignment.End; else -> Alignment.CenterHorizontally }
    ) { index ->
        if (cupertinoHasSlot(node, "item_$index")) cupertinoSlot(node, "item_$index") else CupertinoText(items[index])
    }
    }
}

@Composable
private fun DateTime(node: ViewNode, native: Boolean) {
    val selected = node.long("selectedDateMillis") ?: node.long("initialSelectedDateMillis") ?: java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay() * 86_400_000L
    val hour = (node.long("hour") ?: node.long("initialHour") ?: 0L).toInt().coerceIn(0, 23)
    val minute = (node.long("minute") ?: node.long("initialMinute") ?: 0L).toInt().coerceIn(0, 59)
    val is24Hour = node.bool("is24Hour") ?: true
    val years = yearRange(node)
    val color = cupertinoColor(node, "containerColor") ?: CupertinoTheme.colorScheme.secondarySystemGroupedBackground
    val currentNode by rememberUpdatedState(node)
    val enabled by rememberUpdatedState(cupertinoEnabled(node))
    if (node.string("style")?.lowercase() in setOf("pager", "calendar")) {
        // Upstream's datetime pager is a TODO. Compose its real calendar and time
        // widgets over one bridge value so this exposed style never calls that TODO.
        val date = key(node.id, years) { rememberCupertinoDatePickerState(selected, years) }
        val time = key(node.id, hour, minute, is24Hour) { rememberCupertinoTimePickerState(hour, minute, is24Hour) }
        LaunchedEffect(date, selected) { if (date.selectedDateMillis != selected) date.setSelection(selected) }
        LaunchedEffect(date, time) {
            snapshotFlow { Triple(date.selectedDateMillis, time.hour, time.minute) }.distinctUntilChanged().drop(1).collect { (d, h, m) ->
                val current = currentNode
                if (enabled && (d != current.long("selectedDateMillis") || h.toLong() != current.long("hour") || m.toLong() != current.long("minute")))
                    cupertinoStringEvent(current, "onSelectionChange", buildJsonObject { put("selectedDateMillis", d); put("hour", h); put("minute", m) })
            }
        }
        Column(node.composeModifiers()) {
            CupertinoDatePicker(date, style = cupertinoDateStyle(node), containerColor = color)
            CupertinoTimePicker(time, containerColor = color)
        }
    } else {
        val state = key(node.id, selected, hour, minute, is24Hour, years) {
            rememberCupertinoDateTimePickerState(selected, hour, minute, is24Hour, years)
        }
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.selectedDateTimeMillis, state.selectedHour, state.selectedMinute) }.distinctUntilChanged().drop(1).collect { (d, h, m) ->
                val day = d - (h * 60L + m) * 60_000L
                val current = currentNode
                if (enabled && (day != current.long("selectedDateMillis") || h.toLong() != current.long("hour") || m.toLong() != current.long("minute")))
                    cupertinoStringEvent(current, "onSelectionChange", buildJsonObject { put("selectedDateMillis", day); put("hour", h); put("minute", m) })
            }
        }
        if (native) CupertinoDateTimePickerNative(state, modifier = node.composeModifiers(), style = cupertinoDateStyle(node), containerColor = color)
        else CupertinoDateTimePicker(state, style = cupertinoDateStyle(node), containerColor = color, modifier = node.composeModifiers())
    }
}

/** Upstream date/time widgets lack enabled; block pointer/keyboard and callbacks. */
@Composable
internal fun CupertinoPickerInputGate(enabled: Boolean, content: @Composable () -> Unit) {
    val gate = if (enabled) Modifier else Modifier.semantics { disabled() }
        .onPreviewKeyEvent { true }.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    Box(gate) { content() }
}

@Composable
internal fun cupertinoWheelIndicator(node: ViewNode): CupertinoPickerIndicator =
    if (node.string("indicatorStyle")?.lowercase() == "legacy") CupertinoPickerDefaults.indicatorOld(
        color = cupertinoColor(node, "indicatorColor") ?: cupertinoColor(node, "color") ?: CupertinoTheme.colorScheme.separator)
    else CupertinoPickerDefaults.indicator(
        color = cupertinoColor(node, "indicatorColor") ?: cupertinoColor(node, "color") ?: CupertinoTheme.colorScheme.label.copy(alpha = .05f),
        shape = cupertinoShape(node, "indicatorShape", CupertinoTheme.shapes.small),
        paddingValues = cupertinoPadding(node, "indicatorPadding", PaddingValues(horizontal = 10.dp)))
