package dev.ide.applog.backup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cooperating-app selected USER data only. No Context, app directory discovery, cloud, or device-bound key.
 * V1 is an uncompressed, length-bounded binary archive inside sequenced 32KiB AES-256-GCM records; ZIP is
 * deliberately unsupported. A mandatory authenticated final record prevents truncation/reordering and
 * bounds provider crypto buffering. Only KDF parameters, salt, nonce and record sizes are public; even
 * names/schema/hashes are encrypted. The complete archive is authenticated before importer callbacks.
 */
public final class PortableBackup {
    private static final byte[] MAGIC = new byte[] { 'I', 'D', 'E', 'B', 'K', 'P', '0', '1' };
    private static final int BODY_MAGIC = 0x424B5031;
    private static final int END_MAGIC = 0x454E4431;
    private static final int HEADER_BYTES = 8 + 1 + 4 + 16 + 12;
    private static final int CHUNK = 32768;
    private final Limits limits;
    private final SecureRandom random;

    public static final class Limits {
        public final int maxEntries;
        public final long maxEntryBytes;
        public final long maxAttachmentBytes;
        public final long maxTotalBytes;
        public final long maxArchiveBytes;
        public final int pbkdf2Iterations;
        public Limits(int maxEntries, long maxEntryBytes, long maxAttachmentBytes,
                      long maxTotalBytes, long maxArchiveBytes, int pbkdf2Iterations) {
            if (maxEntries < 1 || maxEntries > 1024 || maxEntryBytes < 1 || maxAttachmentBytes < 1
                    || maxTotalBytes < 1 || maxArchiveBytes < 1024 || maxArchiveBytes > 1024L * 1024 * 1024
                    || maxEntryBytes > maxTotalBytes || maxAttachmentBytes > maxTotalBytes
                    || maxTotalBytes > maxArchiveBytes || pbkdf2Iterations < 100000 || pbkdf2Iterations > 1000000)
                throw new IllegalArgumentException("Invalid bounded backup limits");
            this.maxEntries = maxEntries;
            this.maxEntryBytes = maxEntryBytes;
            this.maxAttachmentBytes = maxAttachmentBytes;
            this.maxTotalBytes = maxTotalBytes;
            this.maxArchiveBytes = maxArchiveBytes;
            this.pbkdf2Iterations = pbkdf2Iterations;
        }
        public static Limits defaults() {
            return new Limits(128, 16L << 20, 32L << 20, 48L << 20, 64L << 20, 310000);
        }
        long entryLimit(BackupManifest.Entry entry) {
            return entry.type() == BackupManifest.Type.USER_ATTACHMENT ? maxAttachmentBytes : maxEntryBytes;
        }
    }

    public interface Cancellation {
        boolean isCancelled();
        Cancellation NONE = new Cancellation() { @Override public boolean isCancelled() { return false; } };
    }
    public interface SnapshotWork { void run() throws Exception; }
    public interface Snapshot {
        /**
         * MUST synchronously invoke work exactly once while holding the application's read lock/database
         * snapshot across ALL exporter reads. Merely copying files while the app writes is not consistent.
         */
        void withConsistentSnapshot(SnapshotWork work) throws Exception;
    }
    public interface Exporter {
        /** Only a manifest-whitelisted entry is requested. Exclude secrets even when embedded in user data. */
        InputStream openSelectedEntry(BackupManifest.Entry entry) throws Exception;
    }
    public interface Importer {
        /** Called only after complete authentication, selection, size and digest validation. */
        Transaction begin(BackupManifest source, BackupManifest target) throws Exception;
    }
    public interface Transaction extends AutoCloseable {
        /** Migrate/validate into an app-owned transaction or staging area. Never mutate visible data here. */
        void stage(VerifiedBackup source, Cancellation cancellation) throws Exception;
        /**
         * MUST atomically commit ALL selected entries using the app's database transaction or atomic pointer
         * switch. On an exception/interruption/crash BEFORE that commit point the old destination MUST remain
         * intact; after it, all new data is visible. Never throw after successfully committing. The SDK cannot
         * make arbitrary multi-file writes transactional. This is an explicit application responsibility.
         */
        void commit() throws Exception;
        /** Idempotent rollback; also required if commit throws. */
        void rollback() throws Exception;
        @Override void close() throws Exception;
    }

    /** Read-only staged entry access; valid only during restore's transaction callback. */
    public static final class VerifiedBackup implements Closeable {
        private final BackupManifest manifest;
        private final Map<String, Path> paths;
        private final Map<String, Long> lengths;
        private final List<InputStream> opened = new ArrayList<InputStream>();
        private boolean closed;
        private VerifiedBackup(BackupManifest manifest, Map<String, Path> paths, Map<String, Long> lengths) {
            this.manifest = manifest;
            this.paths = Collections.unmodifiableMap(paths);
            this.lengths = Collections.unmodifiableMap(lengths);
        }
        public BackupManifest manifest() { return manifest; }
        public long length(String selectedName) {
            Long length = lengths.get(selectedName);
            if (length == null) throw new IllegalArgumentException("Entry was not selected");
            return length;
        }
        public synchronized InputStream openEntry(String selectedName) throws IOException {
            if (closed) throw new IOException("Staging lifetime ended");
            Path path = paths.get(selectedName);
            if (path == null) throw new IOException("Entry was not selected");
            InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
            opened.add(stream);
            return stream;
        }
        @Override public synchronized void close() throws IOException {
            closed = true;
            IOException failure = null;
            for (InputStream input : opened) {
                try { input.close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            opened.clear();
            if (failure != null) throw failure;
        }
    }

    public PortableBackup() { this(Limits.defaults()); }
    public PortableBackup(Limits limits) { this(limits, new SecureRandom()); }
    PortableBackup(Limits limits, SecureRandom random) {
        if (limits == null || random == null) throw new IllegalArgumentException("Limits/random required");
        this.limits = limits;
        this.random = random;
    }

    /** New encrypted file is fsynced and atomically installed; failure never truncates an existing archive. */
    public void exportArchive(Path destination, final BackupManifest manifest, BackupKey key,
                              final Snapshot snapshot, final Exporter exporter, final Cancellation cancellation)
            throws Exception {
        if (manifest == null || key == null || snapshot == null || exporter == null || cancellation == null)
            throw new IllegalArgumentException("Explicit selection, key, snapshot, hooks and cancellation required");
        if (manifest.entries().size() > limits.maxEntries) throw new IOException("Too many selected entries");
        Path target = destination.toAbsolutePath().normalize();
        requirePrivateDirectory(target.getParent());
        if (Files.isSymbolicLink(target) || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Archive destination must be a regular file");
        Path temporary = privateTempFile(target.getParent(), ".backup-");
        try {
            check(cancellation);
            byte[] salt = new byte[16];
            byte[] nonce = new byte[12];
            random.nextBytes(salt);
            random.nextBytes(nonce);
            int mode = key.mode();
            int iterations = mode == BackupKey.PASSPHRASE ? limits.pbkdf2Iterations : 0;
            byte[] header = header(mode, iterations, salt, nonce);
            byte[] derived = key.derive(salt, iterations);
            try {
                try (FileOutputStream file = new FileOutputStream(temporary.toFile())) {
                    BoundedOutputStream bounded = new BoundedOutputStream(file, limits.maxArchiveBytes);
                    bounded.write(header);
                    final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                            new RecordEncryptingOutput(bounded, derived, nonce, header, cancellation), CHUNK));
                    try {
                        final AtomicBoolean started = new AtomicBoolean();
                        final AtomicBoolean completed = new AtomicBoolean();
                        final Thread snapshotThread = Thread.currentThread();
                        snapshot.withConsistentSnapshot(new SnapshotWork() {
                            @Override public void run() throws Exception {
                                if (Thread.currentThread() != snapshotThread || !started.compareAndSet(false, true))
                                    throw new IOException("Snapshot must synchronously invoke work exactly once");
                                writeBody(out, manifest, exporter, cancellation);
                                completed.set(true);
                            }
                        });
                        if (!completed.get()) throw new IOException("Snapshot must synchronously export exactly once");
                    } finally { out.close(); }
                    check(cancellation);
                    file.getFD().sync();
                }
            } finally { Arrays.fill(derived, (byte) 0); }
            check(cancellation);
            atomicInstall(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }

    /**
     * Full authentication precedes parsing, then all selection/digest checks precede any importer call.
     * Staging must be app-private (not shared/external storage); generated file names never use archive paths.
     */
    public void restoreArchive(Path archive, Path privateStagingDirectory, BackupManifest selectedTarget,
                               BackupKey key, Importer importer, Cancellation cancellation) throws Exception {
        if (selectedTarget == null || key == null || importer == null || cancellation == null)
            throw new IllegalArgumentException("Selection, key, importer and cancellation required");
        if (selectedTarget.entries().size() > limits.maxEntries) throw new IOException("Too many selected entries");
        check(cancellation);
        requirePrivateDirectory(privateStagingDirectory);
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Archive must be a regular file");
        if (Files.size(archive) > limits.maxArchiveBytes) throw new IOException("Archive exceeds limit");
        Path directory = privateTempDirectory(privateStagingDirectory, ".restore-");
        try {
            Path authenticated = directory.resolve("authenticated.bin");
            decryptArchive(archive, authenticated, key, cancellation);
            try (VerifiedBackup verified = parseAuthenticated(authenticated, directory, selectedTarget, cancellation)) {
                check(cancellation);
                Transaction transaction = importer.begin(verified.manifest(), selectedTarget);
                if (transaction == null) throw new IOException("Importer did not provide a transaction");
                try (Transaction owned = transaction) {
                    try {
                        owned.stage(verified, cancellation);
                        check(cancellation);
                        owned.commit();
                    } catch (Exception failure) {
                        try { owned.rollback(); } catch (Exception rollback) { failure.addSuppressed(rollback); }
                        throw failure;
                    } catch (Error failure) {
                        try { owned.rollback(); } catch (Exception rollback) { failure.addSuppressed(rollback); }
                        throw failure;
                    }
                }
            }
        } finally { removeStaging(directory); }
    }

    private void writeBody(DataOutputStream out, BackupManifest manifest, Exporter exporter, Cancellation cancellation)
            throws Exception {
        out.writeInt(BODY_MAGIC);
        writeText(out, manifest.applicationId());
        writeText(out, manifest.schemaId());
        out.writeInt(manifest.schemaVersion());
        out.writeInt(manifest.entries().size());
        long total = 0;
        byte[] buffer = new byte[CHUNK];
        for (BackupManifest.Entry entry : manifest.entries()) {
            check(cancellation);
            writeText(out, entry.name());
            out.writeByte(entry.type().wireId);
            long length = 0;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream opened = exporter.openSelectedEntry(entry);
            if (opened == null) throw new IOException("Exporter returned no entry stream");
            try (InputStream input = opened) {
                int count;
                while ((count = readProgress(input, buffer)) >= 0) {
                    check(cancellation);
                    length += count;
                    total += count;
                    if (length > limits.entryLimit(entry) || total > limits.maxTotalBytes)
                        throw new IOException("Selected data exceeds limit");
                    out.writeInt(count);
                    out.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
            }
            out.writeInt(0);
            out.writeLong(length);
            out.write(digest.digest());
        }
        out.writeInt(END_MAGIC);
        Arrays.fill(buffer, (byte) 0);
    }

    private void decryptArchive(Path archive, Path plaintext, BackupKey key, Cancellation cancellation) throws Exception {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS));
             OutputStream raw = Files.newOutputStream(plaintext, java.nio.file.StandardOpenOption.CREATE_NEW,
                     java.nio.file.StandardOpenOption.WRITE)) {
            makePrivate(plaintext, false);
            DataInputStream data = new DataInputStream(input);
            byte[] header = new byte[HEADER_BYTES];
            data.readFully(header);
            DataInputStream fields = new DataInputStream(new java.io.ByteArrayInputStream(header));
            byte[] magic = new byte[8];
            fields.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) throw new IOException("Unsupported backup format");
            int mode = fields.readUnsignedByte();
            int iterations = fields.readInt();
            if ((mode != BackupKey.PASSPHRASE && mode != BackupKey.RECOVERY) || mode != key.mode())
                throw new IOException("Key type does not match archive");
            if (mode == BackupKey.PASSPHRASE && (iterations < 100000 || iterations > 1000000)
                    || mode == BackupKey.RECOVERY && iterations != 0) throw new IOException("Invalid KDF parameters");
            byte[] salt = new byte[16];
            byte[] nonce = new byte[12];
            fields.readFully(salt);
            fields.readFully(nonce);
            byte[] derived = key.derive(salt, iterations);
            BoundedOutputStream bounded = new BoundedOutputStream(raw, limits.maxArchiveBytes);
            long size = HEADER_BYTES;
            long sequence = 0;
            try {
                while (true) {
                    check(cancellation);
                    int length = data.readInt();
                    if (length < 0 || length > CHUNK) throw new IOException("Invalid encrypted backup record");
                    size += 4L + length + 16;
                    if (size > limits.maxArchiveBytes) throw new IOException("Archive exceeds limit");
                    byte[] encrypted = new byte[length + 16];
                    data.readFully(encrypted);
                    byte[] decoded;
                    try {
                        decoded = recordCipher(Cipher.DECRYPT_MODE, derived, nonce, header, sequence++, length).doFinal(encrypted);
                    } catch (GeneralSecurityException invalid) {
                        throw new IOException("Archive authentication failed (wrong key or corrupt archive)");
                    }
                    try {
                        if (decoded.length != length) throw new IOException("Invalid authenticated record length");
                        if (length == 0) {
                            if (data.read() != -1) throw new IOException("Unexpected archive trailer");
                            break;
                        }
                        bounded.write(decoded);
                    } finally { Arrays.fill(decoded, (byte) 0); }
                }
            } catch (EOFException truncated) { throw new IOException("Truncated encrypted backup record"); }
            finally { Arrays.fill(derived, (byte) 0); }
        }
    }

    private VerifiedBackup parseAuthenticated(Path authenticated, Path directory, BackupManifest target,
                                               Cancellation cancellation) throws Exception {
        Map<String, Path> paths = new LinkedHashMap<String, Path>();
        Map<String, Long> lengths = new LinkedHashMap<String, Long>();
        List<BackupManifest.Entry> entries = new ArrayList<BackupManifest.Entry>();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(authenticated)))) {
            if (input.readInt() != BODY_MAGIC) throw new IOException("Invalid backup body version");
            String app = readText(input, 200);
            String schema = readText(input, 128);
            int version = input.readInt();
            int count = input.readInt();
            if (count < 1 || count > limits.maxEntries) throw new IOException("Invalid entry count");
            long total = 0;
            byte[] buffer = new byte[CHUNK];
            for (int i = 0; i < count; i++) {
                check(cancellation);
                BackupManifest.Entry entry;
                try { entry = new BackupManifest.Entry(readText(input, 240), BackupManifest.Type.fromWire(input.readUnsignedByte())); }
                catch (IllegalArgumentException invalid) { throw new IOException("Invalid backup entry"); }
                if (paths.containsKey(entry.name())) throw new IOException("Duplicate archive entry");
                entries.add(entry);
                Path path = directory.resolve("entry-" + i);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long length = 0;
                try (OutputStream output = Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE)) {
                    makePrivate(path, false);
                    while (true) {
                        check(cancellation);
                        int chunk = input.readInt();
                        if (chunk == 0) break;
                        if (chunk < 1 || chunk > CHUNK) throw new IOException("Invalid archive chunk");
                        length += chunk;
                        total += chunk;
                        if (length > limits.entryLimit(entry) || total > limits.maxTotalBytes)
                            throw new IOException("Archive expanded data exceeds limit");
                        input.readFully(buffer, 0, chunk);
                        output.write(buffer, 0, chunk);
                        digest.update(buffer, 0, chunk);
                    }
                }
                if (input.readLong() != length) throw new IOException("Entry size mismatch");
                byte[] expected = new byte[32];
                input.readFully(expected);
                if (!MessageDigest.isEqual(expected, digest.digest())) throw new IOException("Entry integrity mismatch");
                paths.put(entry.name(), path);
                lengths.put(entry.name(), length);
            }
            if (input.readInt() != END_MAGIC || input.read() != -1) throw new IOException("Invalid archive terminator");
            Arrays.fill(buffer, (byte) 0);
            BackupManifest manifest;
            try {
                manifest = new BackupManifest(app, schema, version, entries);
                manifest.requireCompatibleSelection(target);
            } catch (IllegalArgumentException invalid) { throw new IOException("Archive application/schema/selection mismatch"); }
            return new VerifiedBackup(manifest, paths, lengths);
        } catch (EOFException truncated) { throw new IOException("Truncated backup body"); }
    }

    private static byte[] header(int mode, int iterations, byte[] salt, byte[] nonce) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(HEADER_BYTES);
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC); out.writeByte(mode); out.writeInt(iterations); out.write(salt); out.write(nonce);
        return bytes.toByteArray();
    }
    static Cipher recordCipher(int operation, byte[] derived, byte[] baseNonce, byte[] header, long sequence, int length)
            throws GeneralSecurityException {
        byte[] nonce = baseNonce.clone();
        for (int i = 0; i < 8; i++) nonce[nonce.length - 1 - i] ^= (byte) (sequence >>> (8 * i));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(operation, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(header);
        // Length zero is the authenticated end marker; sequence and size bind ordering and framing.
        byte[] context = java.nio.ByteBuffer.allocate(12).putLong(sequence).putInt(length).array();
        cipher.updateAAD(context);
        return cipher;
    }
    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String readText(DataInputStream in, int max) throws IOException {
        int size = in.readInt();
        if (size < 1 || size > max) throw new IOException("Invalid archive string length");
        byte[] bytes = new byte[size]; in.readFully(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes)) throw new IOException("Invalid UTF-8");
        return text;
    }
    private static int readProgress(InputStream input, byte[] buffer) throws IOException {
        int count = input.read(buffer);
        if (count != 0) return count;
        int single = input.read();
        if (single == -1) return -1;
        buffer[0] = (byte) single;
        return 1;
    }
    public static void check(Cancellation cancellation) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancellation.isCancelled())
            throw new InterruptedIOException("Backup cancelled before commit");
    }
    private static void atomicInstall(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Atomic replacement unsupported; previous destination left intact", unsupported);
        }
    }
    private static void requirePrivateDirectory(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Caller must provide an existing app-private directory");
    }
    private static Path privateTempFile(Path parent, String prefix) throws IOException {
        Path path = Files.createTempFile(parent, prefix, ".tmp");
        try { makePrivate(path, false); return path; }
        catch (IOException failure) { Files.deleteIfExists(path); throw failure; }
    }
    private static Path privateTempDirectory(Path parent, String prefix) throws IOException {
        Path path = Files.createTempDirectory(parent, prefix);
        try { makePrivate(path, true); return path; }
        catch (IOException failure) { Files.deleteIfExists(path); throw failure; }
    }
    private static void makePrivate(Path path, boolean directory) throws IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); }
        catch (UnsupportedOperationException unavailable) {
            // On non-POSIX hosts the caller's private-directory ACL is the boundary. Android API26 is POSIX.
        }
    }
    private static void removeStaging(Path directory) throws IOException {
        // Only flat, SDK-generated files are present. Never follow links or recursively traverse user data.
        IOException failure = null;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                try { Files.deleteIfExists(entry); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
        }
        try { Files.deleteIfExists(directory); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }
    private static final class RecordEncryptingOutput extends OutputStream {
        private final DataOutputStream output;
        private final byte[] key, nonce, header;
        private final Cancellation cancellation;
        private final byte[] buffer = new byte[CHUNK];
        private int count;
        private long sequence;
        private boolean closed;
        RecordEncryptingOutput(OutputStream output, byte[] key, byte[] nonce, byte[] header, Cancellation cancellation) {
            this.output = new DataOutputStream(output); this.key = key; this.nonce = nonce; this.header = header;
            this.cancellation = cancellation;
        }
        @Override public void write(int value) throws IOException {
            if (closed) throw new IOException("Archive encryption closed");
            buffer[count++] = (byte) value;
            if (count == buffer.length) emit();
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (closed) throw new IOException("Archive encryption closed");
            while (length > 0) {
                int copied = Math.min(length, buffer.length - count);
                System.arraycopy(bytes, offset, buffer, count, copied);
                count += copied; offset += copied; length -= copied;
                if (count == buffer.length) emit();
            }
        }
        private void emit() throws IOException {
            check(cancellation);
            try {
                byte[] encrypted = recordCipher(Cipher.ENCRYPT_MODE, key, nonce, header, sequence++, count).doFinal(buffer, 0, count);
                output.writeInt(count); output.write(encrypted);
            } catch (GeneralSecurityException failed) { throw new IOException("Backup encryption failed", failed); }
            finally { Arrays.fill(buffer, (byte) 0); count = 0; }
        }
        @Override public void flush() throws IOException { output.flush(); }
        @Override public void close() throws IOException {
            if (closed) return;
            try {
                if (count > 0) emit();
                emit(); // Mandatory authenticated final record, even for an empty stream.
                output.flush();
            } finally { closed = true; Arrays.fill(buffer, (byte) 0); }
        }
    }
    private static final class BoundedOutputStream extends FilterOutputStream {
        private final long maximum;
        private long count;
        BoundedOutputStream(OutputStream out, long maximum) { super(out); this.maximum = maximum; }
        private void reserve(int length) throws IOException {
            if (length > maximum - count) throw new IOException("Archive exceeds limit");
            count += length;
        }
        @Override public void write(int b) throws IOException { reserve(1); out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { reserve(len); out.write(b, off, len); }
    }
}
