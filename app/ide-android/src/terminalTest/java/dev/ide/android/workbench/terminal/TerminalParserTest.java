package dev.ide.android.workbench.terminal;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Runs unchanged both as a plain JVM JUnit test and as Android instrumentation. */
public class TerminalParserTest {
    static final class Output extends TerminalOutput {
        final ByteArrayOutputStream replies = new ByteArrayOutputStream();
        @Override public void write(byte[] data, int offset, int count) { replies.write(data, offset, count); }
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
        String reply() { return new String(replies.toByteArray(), StandardCharsets.UTF_8); }
    }
    private final Output output = new Output();
    private final TerminalEmulator terminal = new TerminalEmulator(output, 40, 6, 8, 16, 100, new TerminalSessionClient() {});
    private void append(String text) { byte[] data = text.getBytes(StandardCharsets.UTF_8); terminal.append(data, data.length); }
    private String line(int row) { return terminal.getSelectedText(0, row, terminal.mColumns - 1, row).stripTrailing(); }

    @Test public void carriageReturnCursorMovesAndEraseChangeCellsNotAppendOnlyText() {
        append("progress 10%\rprogress 20%\033[K");
        assertEquals("progress 20%", line(0));
        append("\033[2;4Hhello\033[2DXY");
        assertEquals("   helXY", line(1));
        append("\033[2J\033[Hdone");
        assertEquals("done", line(0));
        assertEquals("", line(1));
    }
    @Test public void alternateScreenPreservesPrimaryAndCursor() {
        append("main\033[?1049h\033[Hfull screen\033[?1049l");
        assertFalse(terminal.isAlternateBufferActive());
        assertEquals("main", line(0));
        assertEquals(4, terminal.getCursorCol());
        append("!");
        assertEquals("main!", line(0));
    }
    @Test public void splitUtf8WideAndCombiningCharactersMaintainColumnState() {
        byte[] bytes = "A界e\u0301".getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) terminal.append(new byte[]{value}, 1);
        assertEquals("A界e\u0301", line(0));
        assertEquals(4, terminal.getCursorCol());
        append("\033[1D!");
        assertEquals("A界!", line(0));
    }
    @Test public void colorAttributesAreStoredAndReset() {
        append("\033[31;1mR\033[0mN");
        long red = terminal.getScreen().allocateFullLineIfNecessary(terminal.getScreen().externalToInternalRow(0)).getStyle(0);
        assertEquals(1, TextStyle.decodeForeColor(red));
        assertNotEquals(0, TextStyle.decodeEffect(red) & TextStyle.CHARACTER_ATTRIBUTE_BOLD);
        long normal = terminal.getScreen().allocateFullLineIfNecessary(terminal.getScreen().externalToInternalRow(0)).getStyle(1);
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.decodeForeColor(normal));
        assertEquals(0, TextStyle.decodeEffect(normal));
    }
    @Test public void deviceStatusAndBracketedPasteGoBackToPty() {
        append("ab\033[6n");
        assertEquals("\033[1;3R", output.reply());
        output.replies.reset();
        append("\033[?2004h");
        terminal.paste("first\nsecond\033[31m");
        assertTrue(output.reply().startsWith("\033[200~"));
        assertTrue(output.reply().endsWith("\033[201~"));
        assertFalse(output.reply().contains("\033[31m"));
    }
    @Test public void resizeReflowsWithoutLosingVisibleTextAndHistoryIsBounded() {
        append("abcdefghijklmnopqrstuvwxyz");
        terminal.resize(12, 6, 8, 16);
        assertTrue(terminal.getScreen().getTranscriptText().contains("abcdefghijklmnopqrstuvwxyz"));
        for (int index = 0; index < 300; index++) append("\r\nrow " + index);
        assertTrue(terminal.getScreen().getActiveTranscriptRows() <= 100);
        assertTrue(terminal.getScreen().getTranscriptText().contains("row 299"));
        terminal.resize(50, 8, 8, 16);
        assertEquals(50, terminal.mColumns);
        assertEquals(8, terminal.mRows);
    }
    @Test public void promptReadinessUsesCursorCellsBecauseTranscriptTrimsSpaces() {
        append("> ");
        assertEquals(">", terminal.getScreen().getTranscriptText());
        assertFalse(terminal.getScreen().getTranscriptText().contains("> "));
        assertEquals(2, terminal.getCursorCol());
        assertEquals(">", terminal.getSelectedText(0, terminal.getCursorRow(), 1, terminal.getCursorRow()));
    }
    @Test public void arrowsRespectApplicationModeAndCtrlKeysAreNotPrintableLabels() {
        // Android KeyEvent constants are inlined by javac, not mocked at runtime on the JVM.
        assertEquals("\033[A", KeyHandler.getCode(android.view.KeyEvent.KEYCODE_DPAD_UP, 0, false, false));
        assertEquals("\033OA", KeyHandler.getCode(android.view.KeyEvent.KEYCODE_DPAD_UP, 0, true, false));
        assertEquals("\r", KeyHandler.getCode(android.view.KeyEvent.KEYCODE_ENTER, 0, false, false));
        assertEquals("\u007f", KeyHandler.getCode(android.view.KeyEvent.KEYCODE_DEL, 0, false, false));
        assertEquals("\033[1;5D", KeyHandler.getCode(android.view.KeyEvent.KEYCODE_DPAD_LEFT, KeyHandler.KEYMOD_CTRL, false, false));
    }
}
