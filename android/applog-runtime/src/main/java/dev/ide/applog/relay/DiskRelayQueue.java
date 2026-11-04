package dev.ide.applog.relay;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Bounded app-private, per-destination outbox. Stores ONLY encrypted payloads, never keys/auth/plain events.
 * Atomic file replacement + fsync occurs before a caller may ACK its independent SDK destination journal.
 * A caller-scheduled RelayClient.flushOnce is the only network work; no thread, timer, or auto-reconnect.
 */
public final class DiskRelayQueue {
    private static final int MAGIC = 0x52515131;
    private final Path directory;
    private final RelayConfig config;
    private final long maxRecordBytes;
    private long dropped;

    public static final class Pending {
        public final RelayBatch batch;
        public final long createdAtMillis;
        public final int attempts;
        public final long notBeforeMillis;
        private final byte[] envelope;
        Pending(RelayBatch batch, byte[] envelope, long createdAtMillis, int attempts, long notBeforeMillis) {
            this.batch = batch; this.envelope = envelope;
            this.createdAtMillis = createdAtMillis; this.attempts = attempts; this.notBeforeMillis = notBeforeMillis;
        }
        public byte[] encryptedPayload() { return envelope.clone(); }
    }

    /** Parent must be caller-supplied app-private storage; namespace is bound to endpoint + app + recipient. */
    public DiskRelayQueue(Path privateParent, RelayConfig config, String recipientKeyId) throws IOException {
        if (privateParent == null || config == null || config.endpoint == null || recipientKeyId == null)
            throw new IllegalArgumentException("Explicit private queue destination required");
        if (!Files.isDirectory(privateParent, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Queue parent must be private directory");
        this.config = config;
        this.maxRecordBytes = (long) config.maxBatchBytes + 160 * 1024;
        String namespace = config.endpoint.toASCIIString() + "\n" + config.applicationId + "\n" + recipientKeyId;
        this.directory = privateParent.resolve("relay-" + RelayCrypto.hex(RelayCrypto.sha256(
                namespace.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectory(directory); } catch (java.nio.file.FileAlreadyExistsException race) { /* validate below */ }
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Queue must not be a symlink");
        makePrivate(directory, true);
    }

    /** Returns false when full, leaving the source journal unacknowledged. Existing batch IDs are idempotent. */
    public synchronized boolean enqueue(final RelayBatch batch, final byte[] encryptedPayload, final long now)
            throws IOException {
        if (!config.applicationId.equals(batch.applicationId) || batch.scope == RelayScope.BACKUP_DOWNLOAD
                || encryptedPayload == null || encryptedPayload.length > config.maxBatchBytes + 128 || now < 0)
            throw new IOException("Invalid queued relay batch");
        return locked(new Operation<Boolean>() {
            @Override public Boolean run() throws IOException {
                List<Pending> records = loadAndPrune(now);
                long total = 0;
                for (Pending record : records) {
                    if (record.batch.batchId.equals(batch.batchId)) {
                        if (!java.util.Arrays.equals(record.batch.context(), batch.context()))
                            throw new IOException("Batch ID collision");
                        return true;
                    }
                    total += Files.size(file(record.batch.batchId));
                }
                Pending pending = new Pending(batch, encryptedPayload.clone(), now, 0, now);
                byte[] encoded = encode(pending);
                if (records.size() >= config.maxQueueEntries || total + encoded.length > config.maxQueueBytes) return false;
                persist(pending.batch.batchId, encoded);
                return true;
            }
        });
    }
    public synchronized Pending next(final long now) throws IOException {
        return locked(new Operation<Pending>() {
            @Override public Pending run() throws IOException {
                for (Pending pending : loadAndPrune(now)) if (pending.notBeforeMillis <= now) return pending;
                return null;
            }
        });
    }
    public synchronized void retry(final Pending pending, final long notBeforeMillis) throws IOException {
        locked(new Operation<Void>() {
            @Override public Void run() throws IOException {
                Path path = file(pending.batch.batchId);
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
                Pending current = read(path);
                int attempts = Math.max(current.attempts, pending.attempts) + 1;
                if (attempts >= config.maxAttempts) { Files.delete(path); syncDirectory(); dropped++; }
                else persist(current.batch.batchId, encode(new Pending(current.batch, current.envelope,
                        current.createdAtMillis, attempts, Math.max(current.notBeforeMillis, notBeforeMillis))));
                return null;
            }
        });
    }
    /** Call only after RelayCrypto verified the endpoint's durable receipt. */
    synchronized void acknowledge(final Pending pending) throws IOException {
        remove(pending, false);
    }
    synchronized void discard(final Pending pending) throws IOException { remove(pending, true); }
    private void remove(final Pending pending, final boolean countDrop) throws IOException {
        locked(new Operation<Void>() {
            @Override public Void run() throws IOException {
                if (Files.deleteIfExists(file(pending.batch.batchId))) { syncDirectory(); if (countDrop) dropped++; }
                return null;
            }
        });
    }
    /** Explicit local deletion, including revoked-consent outbox; does not make a remote request. */
    public synchronized void clear() throws IOException {
        locked(new Operation<Void>() {
            @Override public Void run() throws IOException {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                    for (Path path : files) {
                        String name = path.getFileName().toString();
                        if (name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.rq") || name.startsWith(".pending-")) {
                            if (Files.deleteIfExists(path)) dropped++;
                        }
                    }
                }
                syncDirectory(); return null;
            }
        });
    }
    public synchronized int size(final long now) throws IOException {
        return locked(new Operation<Integer>() { @Override public Integer run() throws IOException { return loadAndPrune(now).size(); } });
    }
    /** In-process counter only; deliberately no data/error-text telemetry. */
    public synchronized long droppedCount() { return dropped; }

    private List<Pending> loadAndPrune(long now) throws IOException {
        List<Pending> records = new ArrayList<Pending>();
        boolean changed = false;
        long loadedBytes = 0;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (name.startsWith(".pending-")) { Files.deleteIfExists(path); changed = true; continue; }
                if (!name.endsWith(".rq")) continue;
                try {
                    long recordSize = Files.size(path);
                    if (records.size() >= config.maxQueueEntries || recordSize > config.maxQueueBytes - loadedBytes) {
                        Files.deleteIfExists(path); changed = true; dropped++; continue;
                    }
                    Pending pending = read(path);
                    if (now < pending.createdAtMillis || now - pending.createdAtMillis > config.maxQueueAgeMillis
                            || pending.attempts >= config.maxAttempts) {
                        Files.deleteIfExists(path); changed = true; dropped++;
                    } else { records.add(pending); loadedBytes += recordSize; }
                } catch (IOException | IllegalArgumentException invalid) {
                    Files.deleteIfExists(path); changed = true; dropped++;
                }
            }
        }
        Collections.sort(records, new Comparator<Pending>() {
            @Override public int compare(Pending left, Pending right) {
                int time = Long.compare(left.createdAtMillis, right.createdAtMillis);
                return time == 0 ? left.batch.batchId.compareTo(right.batch.batchId) : time;
            }
        });
        long bytes = 0;
        for (Pending pending : records) bytes += Files.size(file(pending.batch.batchId));
        while (records.size() > config.maxQueueEntries || bytes > config.maxQueueBytes) {
            Pending removed = records.remove(0);
            Path path = file(removed.batch.batchId);
            bytes -= Files.size(path); Files.delete(path); changed = true; dropped++;
        }
        if (changed) syncDirectory();
        return records;
    }
    private Path file(String batchId) throws IOException {
        try { RelayBatch.identifier(batchId, 128); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid queue batch identifier"); }
        return directory.resolve(batchId + ".rq");
    }
    private byte[] encode(Pending pending) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeLong(pending.createdAtMillis); out.writeInt(pending.attempts); out.writeLong(pending.notBeforeMillis);
        byte[] context = pending.batch.context();
        out.writeInt(context.length); out.write(context); out.writeInt(pending.envelope.length); out.write(pending.envelope);
        byte[] body = bytes.toByteArray();
        out.write(RelayCrypto.sha256(body));
        if (bytes.size() > maxRecordBytes) throw new IOException("Queue record exceeds bound");
        return bytes.toByteArray();
    }
    private Pending read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Queue record is not regular file");
        long size = Files.size(path);
        if (size < 64 || size > maxRecordBytes) throw new IOException("Invalid queue record length");
        byte[] bytes = new byte[(int) size];
        try (DataInputStream file = new DataInputStream(new BufferedInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)))) {
            file.readFully(bytes);
            if (file.read() != -1) throw new IOException("Queue record changed");
        }
        byte[] body = java.util.Arrays.copyOf(bytes, bytes.length - 32);
        byte[] hash = java.util.Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        if (!java.security.MessageDigest.isEqual(hash, RelayCrypto.sha256(body))) throw new IOException("Corrupt queue record");
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(body));
        if (input.readInt() != MAGIC) throw new IOException("Invalid queue record version");
        long created = input.readLong(); int attempts = input.readInt(); long notBefore = input.readLong();
        if (created < 0 || attempts < 0 || notBefore < created
                || notBefore - created > config.maxQueueAgeMillis + config.maxBackoffMillis)
            throw new IOException("Invalid queue retry metadata");
        int contextLength = input.readInt();
        if (contextLength < 1 || contextLength > 150 * 1024 || contextLength > input.available()) throw new IOException("Invalid queue context");
        byte[] context = new byte[contextLength]; input.readFully(context);
        DataInputStream fields = new DataInputStream(new ByteArrayInputStream(context));
        if (fields.readInt() != 1) throw new IOException("Unsupported queue context");
        String app = RelayCrypto.readText(fields, 200), id = RelayCrypto.readText(fields, 128);
        RelayScope scope = RelayScope.fromWire(RelayCrypto.readText(fields, 32));
        String resource = RelayCrypto.readText(fields, 128);
        int count = fields.readInt();
        if (count < 0 || count > 1024) throw new IOException("Invalid queue incident count");
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < count; i++) ids.add(RelayCrypto.readText(fields, 128));
        RelayBatch batch = new RelayBatch(app, id, scope, resource, ids);
        if (!config.applicationId.equals(app) || scope == RelayScope.BACKUP_DOWNLOAD || fields.available() != 0
                || !path.getFileName().toString().equals(id + ".rq")) throw new IOException("Queue destination mismatch");
        int length = input.readInt();
        if (length < 1 || length > config.maxBatchBytes + 128 || length != input.available()) throw new IOException("Invalid queue payload");
        byte[] envelope = new byte[length]; input.readFully(envelope);
        return new Pending(batch, envelope, created, attempts, notBefore);
    }
    private void persist(String id, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(directory, ".pending-", ".tmp");
        try {
            makePrivate(temporary, false);
            try (FileOutputStream file = new FileOutputStream(temporary.toFile())) {
                file.write(bytes); file.getFD().sync();
            }
            Files.move(temporary, file(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory();
        } finally { Files.deleteIfExists(temporary); }
    }
    private void syncDirectory() throws IOException {
        // Android/Linux/macOS support opening the private directory; no silent durability fallback.
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
    private interface Operation<T> { T run() throws IOException; }
    private <T> T locked(Operation<T> operation) throws IOException {
        Path lockFile = directory.resolve(".lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            makePrivate(lockFile, false);
            try (FileLock ignored = channel.tryLock()) {
                if (ignored == null) throw new IOException("Relay queue busy");
                return operation.run();
            } catch (OverlappingFileLockException busy) { throw new IOException("Relay queue busy"); }
        }
    }
    private static void makePrivate(Path path, boolean directory) throws IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); }
        catch (UnsupportedOperationException unavailable) { /* Caller must supply a private ACL on non-POSIX systems. */ }
    }
}
