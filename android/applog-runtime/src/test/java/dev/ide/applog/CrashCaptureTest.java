package dev.ide.applog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CrashCaptureTest {
    @TempDir File temp;

    @Test void fatalIsDurableBeforePreviousHandlerAndRepeatedFatalCannotOverwrite() throws Exception {
        ConsentState consent = ConsentState.open(new File(temp, "sdk"), false);
        File process = new File(consent.root, "process");
        DiskJournal store = new DiskJournal(process, "sample.app", DiskJournalTest.config("v1"), consent);
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        AtomicBoolean chained = new AtomicBoolean();
        AtomicBoolean persistedBeforeChain = new AtomicBoolean();
        Class<?> runtimeClass = Class.forName("dev.ide.applog.Reliability$RuntimeState");
        Constructor<?> constructor = runtimeClass.getDeclaredConstructor(android.content.Context.class, Config.class, DiskJournal.class, ConsentState.class);
        constructor.setAccessible(true);
        Object runtime = constructor.newInstance(null, DiskJournalTest.config("v1"), store, consent);
        Method install = runtimeClass.getDeclaredMethod("installCrash"); install.setAccessible(true);
        Method close = runtimeClass.getDeclaredMethod("close", boolean.class); close.setAccessible(true);
        try {
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                chained.set(true);
                try { persistedBeforeChain.set(DurableFiles.read(new File(process, "fatal.bin"), DiskJournal.FATAL_MAX_BYTES).contains("fatal_java")); }
                catch (Exception e) { throw new AssertionError(e); }
            });
            install.invoke(runtime);
            Thread.UncaughtExceptionHandler installed = Thread.getDefaultUncaughtExceptionHandler();
            installed.uncaughtException(Thread.currentThread(), new IllegalStateException("password=fatal-secret"));
            assertTrue(chained.get()); assertTrue(persistedBeforeChain.get());
            String id = store.snapshot("local", 16).get(0).eventId;
            installed.uncaughtException(Thread.currentThread(), new RuntimeException("second"));
            assertEquals(id, store.snapshot("local", 16).get(0).eventId);
            assertFalse(store.snapshot("local", 16).get(0).redactedText.contains("fatal-secret"));
            assertTrue(store.snapshot("local", 16).get(0).redactedText.contains("java.lang.IllegalStateException"));
        } finally {
            close.invoke(runtime, false);
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
        try (DiskJournal restarted = new DiskJournal(process, "sample.app", DiskJournalTest.config("v1"), consent)) {
            assertEquals(1, restarted.snapshot("local", 16).size());
            assertEquals("fatal_java", restarted.snapshot("local", 16).get(0).type);
        }
    }
}
