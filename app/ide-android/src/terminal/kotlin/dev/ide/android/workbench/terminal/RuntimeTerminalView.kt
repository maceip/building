package dev.ide.android.workbench.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.view.TerminalRenderer
import kotlin.math.ceil
import kotlin.math.floor

/** Canvas cell renderer over the full VT state machine. This is deliberately NOT an output TextField. */
class RuntimeTerminalView(context: Context) : View(context) {
    var session: RuntimeTerminalSession? = null
        set(value) { field = value; scrollRow = 0; updateSize(); invalidate() }
    private val renderer = TerminalRenderer((14 * resources.displayMetrics.scaledDensity).toInt().coerceAtLeast(10), Typeface.MONOSPACE)
    private var scrollRow = 0
    private var scrollFraction = 0f
    private var deadAccent = 0
    private var composing = SpannableStringBuilder()
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            performClick()
            session?.withEmulator { emulator ->
                if (emulator.isMouseTrackingActive && scrollRow == 0) {
                    val column = (event.x / renderer.fontWidth).toInt() + 1
                    val row = (event.y / renderer.fontLineSpacing).toInt() + 1
                    emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, column, row, true)
                    emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, column, row, false)
                }
            }
            showKeyboard()
            return true
        }
        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollFraction += distanceY / renderer.fontLineSpacing
            val rows = scrollFraction.toInt()
            scrollFraction -= rows
            session?.withEmulator { emulator ->
                if (emulator.isMouseTrackingActive && scrollRow == 0) {
                    repeat(kotlin.math.abs(rows).coerceAtMost(30)) {
                        emulator.sendMouseEvent(if (rows > 0) TerminalEmulator.MOUSE_WHEELDOWN_BUTTON else TerminalEmulator.MOUSE_WHEELUP_BUTTON, 1, 1, true)
                    }
                } else scrollRow = (scrollRow + rows).coerceIn(-emulator.screen.activeTranscriptRows, 0)
            }
            invalidate()
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Interactive runtime terminal. Tap to type; swipe to scroll history."
        setBackgroundColor(0xff000000.toInt())
    }

    fun refresh() {
        session?.withEmulator { emulator ->
            if (scrollRow < 0) scrollRow = (scrollRow - emulator.scrollCounter).coerceAtLeast(-emulator.screen.activeTranscriptRows)
            emulator.clearScrollCounter()
        }
        postInvalidateOnAnimation()
    }
    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
    fun sendKey(keyCode: Int, modifiers: Int = 0): Boolean = session?.withEmulator { emulator ->
        val code = KeyHandler.getCode(keyCode, modifiers, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
        if (code == null) false else { scrollRow = 0; session?.send(code); true }
    } ?: false
    fun visibleText(): String = session?.withEmulator { emulator ->
        emulator.getSelectedText(0, scrollRow, emulator.mColumns - 1, scrollRow + emulator.mRows - 1)
    }.orEmpty()

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) { updateSize() }
    private fun updateSize() {
        if (width < 1 || height < 1) return
        val columns = floor(width / renderer.fontWidth).toInt().coerceAtLeast(2)
        // The upstream renderer reserves an ascent offset in addition to the line cells.
        val rows = ((height - renderer.fontLineSpacing) / renderer.fontLineSpacing).coerceAtLeast(2)
        session?.resize(columns, rows, ceil(renderer.fontWidth).toInt(), renderer.fontLineSpacing)
        scrollRow = 0
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        session?.withEmulator { emulator ->
            canvas.drawColor(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
            renderer.render(emulator, canvas, scrollRow.coerceIn(-emulator.screen.activeTranscriptRows, 0), -1, -1, -1, -1)
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            session?.withEmulator { emulator -> scrollRow = (scrollRow - event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt() * 3).coerceIn(-emulator.screen.activeTranscriptRows, 0) }
            invalidate()
            return true
        }
        return super.onGenericMotionEvent(event)
    }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return object : BaseInputConnection(this, true) {
            override fun getEditable(): Editable = composing
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing.replace(0, composing.length, text ?: "")
                Selection.setSelection(composing, composing.length)
                return true
            }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing.clear()
                scrollRow = 0
                session?.send(text?.toString().orEmpty().replace("\n", "\r"))
                return true
            }
            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) commitText(composing.toString(), 1)
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) composing.delete((composing.length - beforeLength).coerceAtLeast(0), composing.length)
                else repeat(beforeLength.coerceIn(0, 100)) { sendKey(KeyEvent.KEYCODE_DEL) }
                repeat(afterLength.coerceIn(0, 100)) { sendKey(KeyEvent.KEYCODE_FORWARD_DEL) }
                return true
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = deleteSurroundingText(beforeLength, afterLength)
            override fun sendKeyEvent(event: KeyEvent) = this@RuntimeTerminalView.dispatchKeyEvent(event)
            override fun performEditorAction(actionCode: Int): Boolean { sendKey(KeyEvent.KEYCODE_ENTER); return true }
            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = composing.takeLast(length.coerceAtLeast(0))
            override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""
            override fun getSelectedText(flags: Int): CharSequence = ""
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Android Back retains its navigation/IME behavior; Esc has its own physical and onscreen key.
        if (keyCode == KeyEvent.KEYCODE_BACK || KeyEvent.isModifierKey(keyCode)) return super.onKeyDown(keyCode, event)
        var modifiers = 0
        if (event.isCtrlPressed) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) modifiers = modifiers or KeyHandler.KEYMOD_NUM_LOCK
        if (sendKey(keyCode, modifiers)) return true
        var point = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (point == 0) return super.onKeyDown(keyCode, event)
        if ((point and KeyCharacterMap.COMBINING_ACCENT) != 0) { deadAccent = point and KeyCharacterMap.COMBINING_ACCENT_MASK; return true }
        if (deadAccent != 0) { point = KeyCharacterMap.getDeadChar(deadAccent, point).takeIf { it != 0 } ?: point; deadAccent = 0 }
        if (event.isCtrlPressed) point = when (point) {
            in 97..122 -> point - 96
            in 64..95 -> point - 64
            32 -> 0
            63 -> 127
            else -> point
        }
        scrollRow = 0
        session?.send((if (event.isAltPressed) "\u001b" else "") + String(Character.toChars(point)))
        return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK || KeyEvent.isModifierKey(keyCode)) super.onKeyUp(keyCode, event) else true

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.EditText"
        info.isEditable = true
        info.text = visibleText().takeLast(8192)
    }
}
