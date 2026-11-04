package dev.ide.applog;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/** Same-directory atomic replacement; a successful return includes both file and directory fsync. */
final class DurableFiles {
    private static final String MAGIC = "IDE-RELIABILITY-1\n";
    private DurableFiles() {}

    static byte[] envelope(String json) {
        byte[] body = json.getBytes(Redactor.UTF8);
        CRC32 crc = new CRC32();
        crc.update(body);
        byte[] prefix = (MAGIC + Long.toHexString(crc.getValue()) + "\n").getBytes(Redactor.UTF8);
        byte[] all = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(body, 0, all, prefix.length, body.length);
        return all;
    }

    static String read(File file, int maxBytes) throws IOException {
        if (!file.exists()) return null;
        if (file.length() < MAGIC.length() + 3 || file.length() > maxBytes) throw new IOException("Invalid journal size");
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = in.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("Truncated journal");
                offset += count;
            }
            if (in.read() != -1) throw new IOException("Journal changed while reading");
        }
        String all = new String(bytes, Redactor.UTF8);
        if (!all.startsWith(MAGIC)) throw new IOException("Invalid journal header");
        int split = all.indexOf('\n', MAGIC.length());
        if (split < 0) throw new IOException("Truncated journal checksum");
        String json = all.substring(split + 1);
        CRC32 crc = new CRC32();
        crc.update(json.getBytes(Redactor.UTF8));
        if (!Long.toHexString(crc.getValue()).equals(all.substring(MAGIC.length(), split))) {
            throw new IOException("Journal checksum mismatch; retained without modification");
        }
        return json;
    }

    enum Operation { FILE_WRITE, FILE_FSYNC, ATOMIC_RENAME, DIRECTORY_API, DIRECTORY_OPEN, DIRECTORY_TYPE, DIRECTORY_FSYNC, DIRECTORY_CLOSE }

    /** Fixed operation plus cause; consumers expose only the operation and exception CLASS, never its message. */
    static final class StorageException extends IOException {
        private static final long serialVersionUID = 1L;
        final Operation operation;
        StorageException(Operation operation, Throwable cause) { super(operation.name(), cause); this.operation = operation; }
    }

    static void replace(File file, byte[] bytes) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        Operation operation = Operation.FILE_WRITE;
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.flush();
                operation = Operation.FILE_FSYNC;
                out.getFD().sync();
            }
            // API 26+, Java 7+. Never fall back to delete-then-rename or a non-atomic copy.
            operation = Operation.ATOMIC_RENAME;
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(file.getParentFile());
        } catch (StorageException error) {
            throw error;
        } catch (Exception | LinkageError error) {
            throw storageFailure(operation, error);
        } finally {
            if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit();
        }
    }

    static void syncDirectory(File directory) throws IOException {
        final Class<?> os;
        try {
            os = Class.forName("android.system.Os");
        } catch (ClassNotFoundException portableJvm) {
            try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) { channel.force(true); }
            catch (Exception | LinkageError error) { throw storageFailure(Operation.DIRECTORY_FSYNC, error); }
            return;
        }
        Operation operation = Operation.DIRECTORY_API;
        FileDescriptor fd = null;
        StorageException pending = null;
        try {
            Class<?> constants = Class.forName("android.system.OsConstants");
            // O_DIRECTORY is NOT an Android public SDK field (hidden API reflection fails on modern ART).
            // Use public flags, then fstat the opened descriptor before the still-mandatory directory fsync.
            int flags = constants.getField("O_RDONLY").getInt(null) | constants.getField("O_CLOEXEC").getInt(null);
            operation = Operation.DIRECTORY_OPEN;
            fd = (FileDescriptor) os.getMethod("open", String.class, int.class, int.class)
                .invoke(null, directory.getAbsolutePath(), flags, 0);
            operation = Operation.DIRECTORY_TYPE;
            Object stat = os.getMethod("fstat", FileDescriptor.class).invoke(null, fd);
            int mode = Class.forName("android.system.StructStat").getField("st_mode").getInt(stat);
            if (!Boolean.TRUE.equals(constants.getMethod("S_ISDIR", int.class).invoke(null, mode))) {
                throw new IOException("Directory descriptor required");
            }
            operation = Operation.DIRECTORY_FSYNC;
            os.getMethod("fsync", FileDescriptor.class).invoke(null, fd);
        } catch (Exception | LinkageError error) {
            pending = storageFailure(operation, error);
            throw pending;
        } finally {
            if (fd != null) {
                try { os.getMethod("close", FileDescriptor.class).invoke(null, fd); }
                catch (Exception | LinkageError error) {
                    StorageException closing = storageFailure(Operation.DIRECTORY_CLOSE, error);
                    if (pending != null) pending.addSuppressed(closing); else throw closing;
                }
            }
        }
    }

    private static StorageException storageFailure(Operation operation, Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
        return new StorageException(operation, cause);
    }
}
