package dev.ide.applog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyAndProtocolTest {
    @TempDir File temp;
    private static Incident event(String text) {
        return new Incident(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "sample.app", "v1", "rev1", 1234L, "breadcrumb", text);
    }

    @Test void defaultsAndMissingConsentNeverOpenAJournal() throws Exception {
        Config defaults = new Config.Builder().build();
        assertFalse(defaults.enabled); assertFalse(defaults.consentGranted); assertFalse(defaults.teeStdout); assertFalse(defaults.captureNetwork);
        assertFalse(defaults.isCollectionAllowed()); assertFalse(defaults.isPaired());
        Config enabled = new Config.Builder().enabled(true).buildId("v1").sourceRevision("rev1").build();
        assertFalse(enabled.isCollectionAllowed());
        assertThrows(IllegalStateException.class, () -> new DiskJournal(new File(temp, "disabled"), "sample.app", enabled));
        assertFalse(new File(temp, "disabled").exists());
        Config missing = Config.fromJson("{\"version\":1,\"enabled\":true,\"buildId\":\"v1\",\"sourceRevision\":\"rev1\"}");
        assertFalse(missing.isCollectionAllowed());
        assertThrows(JSONException.class, () -> Config.fromJson("{\"version\":2}"));
        assertThrows(IllegalArgumentException.class, () -> new Config.Builder().pairingNonce("empty-token-bypass").build());
        assertThrows(IllegalArgumentException.class, () -> new Config.Builder().enabled(true).build());
    }

    @Test void initializationDiagnosticKeepsStageAndCauseClassButNoSecrets() {
        Throwable error = new IOException("/data/user/0/private-path pairingNonce=secret",
            new DurableFiles.StorageException(DurableFiles.Operation.DIRECTORY_API,
                new java.lang.reflect.InvocationTargetException(new NoSuchFieldException("private-field-secret"))));
        String code = Reliability.failureCode(Reliability.InitializationStage.PRIVATE_STORAGE, error);
        assertEquals("initialization_failed:PRIVATE_STORAGE:DIRECTORY_API:java.lang.NoSuchFieldException", code);
        assertFalse(code.contains("private-path")); assertFalse(code.contains("secret"));
        assertFalse(code.contains("pairingNonce")); assertTrue(code.length() < 256);
        Throwable appError = new RuntimeException("user-secret") {};
        assertEquals("initialization_failed:HOOK_INSTALL:NONE:OtherException",
            Reliability.failureCode(Reliability.InitializationStage.HOOK_INSTALL, appError));
    }

    @Test void directorySyncFailureRetainsSpecificOperationWithoutPretendingSuccess() {
        DurableFiles.StorageException error = assertThrows(DurableFiles.StorageException.class,
            () -> DurableFiles.syncDirectory(new File(temp, "missing-directory")));
        assertEquals(DurableFiles.Operation.DIRECTORY_FSYNC, error.operation);
        assertNotNull(error.getCause());
        String code = Reliability.failureCode(Reliability.InitializationStage.PRIVATE_STORAGE, error);
        assertTrue(code.startsWith("initialization_failed:PRIVATE_STORAGE:DIRECTORY_FSYNC:java."));
        assertFalse(code.contains(temp.getAbsolutePath()));
    }

    @Test void textBoundIsUnicodeBytesNotUtf16Chars() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 9000; i++) input.append("\ud83d\ude00");
        Incident e = event(input.toString());
        assertEquals(8192, e.redactedText.getBytes(Redactor.UTF8).length);
        assertFalse(Character.isHighSurrogate(e.redactedText.charAt(e.redactedText.length() - 1)));
        assertTrue(e.toJson().getBytes(Redactor.UTF8).length < IncidentProtocol.MAX_FRAME_BYTES);
    }

    @Test void messageCredentialsAndRawExceptionMessagesNeverPersist() {
        String redacted = Redactor.text("Authorization: Bearer abc\nCookie: sid=123\npassword=secret token=abc api_key=hidden me@example.com https://name:pass@host/path?q=secret");
        assertFalse(redacted.contains("abc")); assertFalse(redacted.contains("sid=123")); assertFalse(redacted.contains("hidden"));
        assertFalse(redacted.contains("me@example.com")); assertFalse(redacted.contains("host/path"));
        Throwable error = new IOException("password=exception-secret body={private payload}");
        String crash = Redactor.exception(error);
        assertTrue(crash.contains("java.io.IOException")); assertFalse(crash.contains("exception-secret")); assertFalse(crash.contains("private payload"));
        String request = "request: 0123456789abcdef method=GET endpoint=https:remote";
        assertEquals(request, Redactor.text(request));
    }

    @Test void publicCodecIsCanonicalAndDoesNotMutateStableIdentity() throws Exception {
        Incident before = event("token=secret normal label");
        String json = before.toJson();
        assertEquals(json, Incident.fromJson(json).toJson());
        assertEquals("[" + json + "]", IncidentProtocol.encodeEvents(Collections.singletonList(before)));
        assertFalse(json.contains("token=secret"));
        JSONObject decoded = new JSONObject(json);
        assertEquals(8, decoded.length());
        assertEquals(1234L, decoded.getLong("timestamp"));
    }

    @Test void receiptsRejectWrongNonceOtherBatchAndConflictingIdentity() throws Exception {
        Incident e = event("hello");
        JSONObject receipt = IncidentProtocol.identity(e, "receipt", "client"); receipt.put("serverNonce", "server");
        receipt.put("ackEventIds", new JSONArray(Collections.singletonList(e.eventId)));
        assertEquals(Collections.singletonList(e.eventId), IncidentProtocol.receipt(receipt.toString(), Collections.singletonList(e), "client", "server"));
        assertThrows(JSONException.class, () -> IncidentProtocol.receipt(receipt.toString(), Collections.singletonList(e), "wrong", "server"));
        assertThrows(JSONException.class, () -> IncidentProtocol.receipt(receipt.toString(), Collections.singletonList(e), "client", "wrong"));
        receipt.put("ackEventIds", new JSONArray(Collections.singletonList(UUID.randomUUID().toString())));
        assertThrows(JSONException.class, () -> IncidentProtocol.receipt(receipt.toString(), Collections.singletonList(e), "client", "server"));
        receipt.put("ackEventIds", new JSONArray(Collections.singletonList(e.eventId))); receipt.put("buildId", "retagged");
        assertThrows(JSONException.class, () -> IncidentProtocol.receipt(receipt.toString(), Collections.singletonList(e), "client", "server"));
        assertThrows(JSONException.class, () -> IncidentProtocol.receipt("{\"version\":1,\"op\":\"error\",\"code\":\"RETRY_LATER\"}", Collections.singletonList(e), "client", "server"));
    }

    @Test void framesEnforceBothByteAndCharacterBoundsAndSingleIdentity() throws Exception {
        StringBuilder tooManyBytes = new StringBuilder();
        for (int i = 0; i < 23000; i++) tooManyBytes.append('\u4e2d');
        assertThrows(JSONException.class, () -> IncidentProtocol.bounded(tooManyBytes.toString()));
        char[] tooManyChars = new char[32769]; Arrays.fill(tooManyChars, 'a');
        assertThrows(JSONException.class, () -> IncidentProtocol.bounded(new String(tooManyChars)));
        assertThrows(JSONException.class, () -> IncidentProtocol.submit(Arrays.asList(event("one"), event("two")), "client", "server", 0));
    }

    @Test void revocationPersistsAndRegrantDoesNotResurrectOldProcessJournal() throws Exception {
        File root = new File(temp, "sdk");
        ConsentState consent = ConsentState.open(root, false);
        String oldGeneration = consent.generation;
        File process = new File(root, "process");
        DiskJournal old = new DiskJournal(process, "sample.app", DiskJournalTest.config("v1"), consent);
        old.registerDestination("relay:fixture");
        old.append(old.incident("breadcrumb", "before revoke"));
        old.appendFatal(old.incident("fatal_java", "old crash"));
        consent.revoke();
        assertFalse(consent.allowed());
        assertNull(ConsentState.open(root, false)); // Asset consent=true is NOT a user regrant.
        assertThrows(IOException.class, () -> old.snapshot("local", 16));
        assertFalse(old.appendFatal(old.incident("fatal_java", "after revoke")));
        old.close();
        ConsentState regranted = ConsentState.open(root, true);
        assertNotEquals(oldGeneration, regranted.generation);
        assertFalse(consent.allowed());
        try (DiskJournal fresh = new DiskJournal(process, "sample.app", DiskJournalTest.config("v2"), regranted)) {
            assertTrue(fresh.snapshot("local", 16).isEmpty());
            assertFalse(fresh.hasDestination("relay:fixture"));
            assertTrue(fresh.append(fresh.incident("breadcrumb", "new consent")));
        }
    }
}
