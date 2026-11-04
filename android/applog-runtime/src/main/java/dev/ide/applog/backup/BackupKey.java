package dev.ide.applog.backup;

import java.io.Closeable;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable user secret. Never put this object/material in a log, archive, Intent, or relay metadata. */
public final class BackupKey implements Closeable {
    static final int PASSPHRASE = 1;
    static final int RECOVERY = 2;
    private final int mode;
    private char[] passphrase;
    private byte[] recovery;
    private boolean closed;

    private BackupKey(char[] passphrase, byte[] recovery, int mode) {
        this.passphrase = passphrase;
        this.recovery = recovery;
        this.mode = mode;
    }

    /** Caller owns and should clear the original char[]. Minimum length is not an entropy guarantee. */
    public static BackupKey passphrase(char[] secret) {
        if (secret == null || secret.length < 12 || secret.length > 1024)
            throw new IllegalArgumentException("Use a strong passphrase of 12 to 1024 characters");
        return new BackupKey(secret.clone(), null, PASSPHRASE);
    }

    public static BackupKey recoveryKey(byte[] secret) {
        if (secret == null || secret.length != 32) throw new IllegalArgumentException("Recovery key must be 256 bits");
        return new BackupKey(null, secret.clone(), RECOVERY);
    }

    /** Present via a deliberate private UI, never telemetry. User must transfer it separately from the archive. */
    public static String newRecoveryCode(SecureRandom random) {
        if (random == null) throw new IllegalArgumentException("Random source required");
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        try { return "bkr1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret); }
        finally { Arrays.fill(secret, (byte) 0); }
    }

    public static BackupKey recoveryCode(String code) {
        if (code == null || !code.matches("bkr1_[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("Invalid recovery code format");
        byte[] decoded;
        try { decoded = Base64.getUrlDecoder().decode(code.substring(5)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid recovery code format"); }
        try {
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(code.substring(5)))
                throw new IllegalArgumentException("Invalid recovery code encoding");
            return recoveryKey(decoded);
        } finally { Arrays.fill(decoded, (byte) 0); }
    }

    synchronized int mode() {
        requireOpen();
        return mode;
    }

    synchronized byte[] derive(byte[] salt, int iterations) throws GeneralSecurityException {
        requireOpen();
        if (mode == PASSPHRASE) {
            if (iterations < 100000 || iterations > 1000000) throw new GeneralSecurityException("Invalid KDF cost");
            PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
            try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
            finally { spec.clearPassword(); }
        }
        if (iterations != 0) throw new GeneralSecurityException("Invalid recovery KDF");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(recovery, "HmacSHA256"));
        mac.update(new byte[] { 'B', 'K', 'R', '1', 0 });
        return mac.doFinal(salt);
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Backup key was cleared"); }
    @Override public synchronized void close() {
        if (passphrase != null) Arrays.fill(passphrase, '\0');
        if (recovery != null) Arrays.fill(recovery, (byte) 0);
        passphrase = null;
        recovery = null;
        closed = true;
    }
    @Override public String toString() { return "BackupKey[redacted]"; }
}
