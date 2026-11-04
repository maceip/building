package dev.ide.applog.relay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mandatory end-to-end envelope. The user provisions the 256-bit recipient key separately on app/receiver;
 * it is NEVER uploaded in the envelope/token/backup. Not an AndroidKeyStore-only cross-device secret.
 * AES-GCM and HMAC receipt keys are context-separated via HMAC-SHA256 PRF derivation from this random key.
 */
public final class RelayCrypto implements Closeable {
    private static final int MAGIC = 0x524C5931; // RLY1
    private final String keyId;
    private final SecureRandom random = new SecureRandom();
    private byte[] master;

    public RelayCrypto(String keyId, byte[] separatelyProvisionedKey) {
        RelayBatch.identifier(keyId, 80);
        if (separatelyProvisionedKey == null || separatelyProvisionedKey.length != 32)
            throw new IllegalArgumentException("Recipient-provisioned 256-bit key required");
        this.keyId = keyId;
        this.master = separatelyProvisionedKey.clone();
    }
    public String keyId() { return keyId; }
    public byte[] encryptRequest(RelayBatch batch, byte[] plaintext, int maximumBytes) throws IOException {
        return encrypt(batch, plaintext, maximumBytes, "request");
    }
    /** Receiver-side adapter/test helper; the endpoint must authorize app/scope BEFORE decrypting. */
    public byte[] decryptRequest(RelayBatch batch, byte[] envelope, int maximumBytes) throws IOException {
        return decrypt(batch, envelope, maximumBytes, "request");
    }
    public byte[] encryptResponse(RelayBatch batch, byte[] plaintext, int maximumBytes) throws IOException {
        return encrypt(batch, plaintext, maximumBytes, "response");
    }
    public byte[] decryptResponse(RelayBatch batch, byte[] envelope, int maximumBytes) throws IOException {
        return decrypt(batch, envelope, maximumBytes, "response");
    }

    /**
     * Receiver MUST durably commit/deduplicate all incident IDs or a backup before issuing a receipt.
     * Downloads sign the EXACT encrypted response. Binding both request/response hashes prevents substitution.
     */
    public byte[] receipt(RelayBatch batch, byte[] requestEnvelope, byte[] responseBody) throws IOException {
        byte[] derived = derive("ack");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derived, "HmacSHA256"));
            mac.update("ide-relay-receipt-v1\0".getBytes(StandardCharsets.US_ASCII));
            mac.update(batch.context());
            mac.update(sha256(requestEnvelope));
            mac.update(sha256(responseBody));
            return mac.doFinal();
        } catch (GeneralSecurityException invalid) { throw new IOException("Relay receipt unavailable", invalid); }
        finally { Arrays.fill(derived, (byte) 0); }
    }
    public boolean verifyReceipt(RelayBatch batch, byte[] requestEnvelope, byte[] responseBody, byte[] receipt)
            throws IOException {
        if (receipt == null || receipt.length != 32) return false;
        byte[] expected = receipt(batch, requestEnvelope, responseBody);
        try { return MessageDigest.isEqual(expected, receipt); }
        finally { Arrays.fill(expected, (byte) 0); }
    }

    private byte[] encrypt(RelayBatch batch, byte[] plaintext, int maximum, String direction) throws IOException {
        if (plaintext == null || maximum < 0 || plaintext.length > maximum)
            throw new IOException("Relay payload exceeds bound");
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        byte[] derived = derive("encryption");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(batch, direction));
            byte[] encrypted = cipher.doFinal(plaintext);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(encrypted.length + 128);
            DataOutputStream output = new DataOutputStream(buffer);
            output.writeInt(MAGIC); RelayBatch.text(output, keyId); output.write(nonce);
            output.writeInt(encrypted.length); output.write(encrypted);
            return buffer.toByteArray();
        } catch (GeneralSecurityException invalid) { throw new IOException("Relay encryption failed", invalid); }
        finally { Arrays.fill(derived, (byte) 0); }
    }
    private byte[] decrypt(RelayBatch batch, byte[] envelope, int maximum, String direction) throws IOException {
        if (maximum < 0 || envelope == null || envelope.length > (long) maximum + 128)
            throw new IOException("Relay envelope exceeds bound");
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(envelope));
        if (input.readInt() != MAGIC) throw new IOException("Unsupported relay envelope");
        String recipient = readText(input, 80);
        if (!keyId.equals(recipient)) throw new IOException("Unknown recipient key");
        byte[] nonce = new byte[12]; input.readFully(nonce);
        int size = input.readInt();
        if (size < 16 || size > (long) maximum + 16 || size != input.available()) throw new IOException("Invalid envelope length");
        byte[] encrypted = new byte[size]; input.readFully(encrypted);
        byte[] derived = derive("encryption");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(batch, direction));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException invalid) { throw new IOException("Relay envelope authentication failed"); }
        finally { Arrays.fill(derived, (byte) 0); }
    }
    private byte[] aad(RelayBatch batch, String direction) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        RelayBatch.text(out, "ide-relay-envelope-v1"); RelayBatch.text(out, direction); RelayBatch.text(out, keyId);
        out.write(batch.context());
        return buffer.toByteArray();
    }
    private synchronized byte[] derive(String use) throws IOException {
        if (master == null) throw new IOException("Relay key cleared");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            mac.update(("ide-relay/v1/" + use + "\0").getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(keyId.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException invalid) { throw new IOException("Relay crypto unavailable", invalid); }
    }
    static String readText(DataInputStream input, int maximum) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > maximum) throw new IOException("Invalid bounded string");
        byte[] bytes = new byte[size]; input.readFully(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes)) throw new IOException("Invalid UTF-8");
        return text;
    }
    static byte[] sha256(byte[] input) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (GeneralSecurityException invalid) { throw new IOException("SHA-256 unavailable", invalid); }
    }
    static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { out[i * 2] = alphabet[(bytes[i] >>> 4) & 15]; out[i * 2 + 1] = alphabet[bytes[i] & 15]; }
        return new String(out);
    }
    @Override public synchronized void close() { if (master != null) Arrays.fill(master, (byte) 0); master = null; }
    @Override public String toString() { return "RelayCrypto[recipient-key-redacted]"; }
}
