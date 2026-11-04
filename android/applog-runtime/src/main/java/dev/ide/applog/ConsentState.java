package dev.ide.applog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** App-wide no-backup consent authority. APK assets cannot override a user's persisted revocation. */
final class ConsentState {
    private static final ReentrantLock JVM_LOCK = new ReentrantLock();
    final File root;
    final String generation;
    private ConsentState(File root, String generation) { this.root = root; this.generation = generation; }

    static ConsentState open(File root, boolean explicitRegrant) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create consent directory");
        DurableFiles.syncDirectory(root.getParentFile());
        JVM_LOCK.lock();
        try (RandomAccessFile lock = new RandomAccessFile(new File(root, "consent.lock"), "rw");
             FileLock ignored = lock.getChannel().lock()) {
            File file = new File(root, "consent.bin");
            String data = DurableFiles.read(file, 4096);
            if (data != null && !explicitRegrant) {
                JSONObject state = new JSONObject(data);
                if (state.getInt("version") != 1) throw new IOException("Unsupported consent state");
                if (!state.getBoolean("allowed")) return null;
                return new ConsentState(root, state.getString("generation"));
            }
            String generation = UUID.randomUUID().toString();
            JSONObject state = new JSONObject(); state.put("version", 1); state.put("allowed", true); state.put("generation", generation);
            DurableFiles.replace(file, DurableFiles.envelope(state.toString()));
            return new ConsentState(root, generation);
        } catch (JSONException e) { throw new IOException("Invalid consent state", e); }
        finally { JVM_LOCK.unlock(); }
    }

    boolean allowed() {
        try {
            String data = DurableFiles.read(new File(root, "consent.bin"), 4096);
            if (data == null) return false;
            JSONObject state = new JSONObject(data);
            return state.getInt("version") == 1 && state.getBoolean("allowed") && generation.equals(state.getString("generation"));
        } catch (Exception e) { return false; }
    }

    void revoke() throws IOException {
        JVM_LOCK.lock();
        try (RandomAccessFile lock = new RandomAccessFile(new File(root, "consent.lock"), "rw");
             FileLock ignored = lock.getChannel().lock()) {
            String previous = DurableFiles.read(new File(root, "consent.bin"), 4096);
            if (previous != null && !generation.equals(new JSONObject(previous).getString("generation"))) return;
            JSONObject state = new JSONObject(); state.put("version", 1); state.put("allowed", false);
            state.put("generation", UUID.randomUUID().toString());
            DurableFiles.replace(new File(root, "consent.bin"), DurableFiles.envelope(state.toString()));
        } catch (JSONException e) { throw new IOException("Cannot persist consent revocation", e); }
        finally { JVM_LOCK.unlock(); }
    }
}
