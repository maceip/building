package dev.ide.android.reliability

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import dev.ide.workbench.reliability.AtomicReliabilityFile
import dev.ide.workbench.reliability.PairingRegistry
import dev.ide.workbench.reliability.ReliabilityBlobStorage
import dev.ide.workbench.reliability.ReliabilityError
import dev.ide.workbench.reliability.ReliabilityException
import java.nio.file.Path
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun androidReliabilityFile(path: Path, maxBytes: Int): ReliabilityBlobStorage = AtomicReliabilityFile(path, maxBytes) { directory ->
    val descriptor = Os.open(directory.toString(), OsConstants.O_RDONLY, 0)
    try {
        if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw java.io.IOException("Expected private directory")
        Os.fsync(descriptor)
    } finally { Os.close(descriptor) }
}

/** No plaintext fallback: an unavailable or invalidated Keystore leaves pairing disabled. */
internal class EncryptedPairingStorage(context: Context, path: Path) : ReliabilityBlobStorage {
    private val file = androidReliabilityFile(path, PairingRegistry.MAX_CONFIG_BYTES + 128)
    private val associatedData = "${context.packageName}:reliability-pairings:v1".toByteArray(Charsets.UTF_8)
    private val alias = "dev.ide.reliability.pairings.v1"

    override fun <T> transaction(operation: () -> T): T = file.transaction(operation)

    override fun read(): ByteArray? = safely {
        val encrypted = file.read() ?: return@safely null
        if (encrypted.size < 30 || encrypted[0] != 1.toByte()) unavailable()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        cipher.updateAAD(associatedData)
        cipher.doFinal(encrypted, 13, encrypted.size - 13)
    }

    override fun write(bytes: ByteArray) = safely {
        if (bytes.size > PairingRegistry.MAX_CONFIG_BYTES) unavailable()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(associatedData)
        if (cipher.iv.size != 12) unavailable()
        file.write(byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes))
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) unavailable()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    private inline fun <T> safely(action: () -> T): T = try { action() } catch (_: Exception) { unavailable() }
    private fun unavailable(): Nothing = throw ReliabilityException(ReliabilityError.STORAGE_UNAVAILABLE)
}
