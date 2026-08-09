package dev.buhanzaz.rwms.worker.core.media

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encapsulates worker evidence/media recovery behavior; server confirmation remains authoritative.
 */
data class EncryptedEvidenceFile(
    val encryptedPath: String,
    val plainSizeBytes: Long,
    val sha256: String,
)

/**
 * JPEGs are encrypted before they become durable evidence. CameraX is allowed
 * to write a short-lived cache file only; [persistJpeg] always removes it.
 */
@Singleton
class EncryptedEvidenceFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File by lazy { File(context.noBackupFilesDir, "worker-evidence").also(File::mkdirs) }

    /**
     * Atomically encrypts a captured JPEG, derives its plaintext SHA-256, and removes the
     * temporary CameraX file on every outcome.
     */
    fun persistJpeg(userId: String, evidenceId: String, temporaryJpeg: File): EncryptedEvidenceFile {
        require(temporaryJpeg.isFile) { "Captured JPEG is missing" }
        require(temporaryJpeg.length() in 1..MAX_JPEG_BYTES) { "Captured JPEG exceeds 15 MiB" }
        val destination = evidenceFile(userId, evidenceId)
        val partial = File(destination.parentFile, "${destination.name}.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            DataOutputStream(BufferedOutputStream(FileOutputStream(partial))).use { header ->
                header.write(MAGIC)
                header.writeInt(cipher.iv.size)
                header.write(cipher.iv)
                CipherOutputStream(header, cipher).use { encrypted ->
                    BufferedInputStream(FileInputStream(temporaryJpeg)).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            encrypted.write(buffer, 0, read)
                            size += read
                        }
                    }
                }
            }
            require(size in 1..MAX_JPEG_BYTES) { "Captured JPEG exceeds 15 MiB" }
            check(partial.renameTo(destination)) { "Could not atomically persist encrypted evidence" }
            return EncryptedEvidenceFile(
                encryptedPath = destination.absolutePath,
                plainSizeBytes = size,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            )
        } finally {
            partial.takeIf(File::exists)?.delete()
            // The transient CameraX output must never outlive the encryption step.
            temporaryJpeg.delete()
        }
    }

    fun openDecrypted(encryptedPath: String): CipherInputStream {
        val file = requireOwnedFile(encryptedPath)
        val raw = BufferedInputStream(FileInputStream(file))
        try {
            val header = DataInputStream(raw)
            val magic = ByteArray(MAGIC.size)
            header.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "Unsupported evidence file format" }
            val ivLength = header.readInt()
            require(ivLength in 12..32) { "Invalid encrypted evidence IV" }
            val iv = ByteArray(ivLength)
            header.readFully(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            }
            return CipherInputStream(raw, cipher)
        } catch (error: Throwable) {
            raw.close()
            throw error
        }
    }

    fun delete(encryptedPath: String) {
        requireOwnedFile(encryptedPath).delete()
    }

    private fun evidenceFile(userId: String, evidenceId: String): File {
        val user = safeUuid(userId)
        val evidence = safeUuid(evidenceId)
        return File(File(root, user).also(File::mkdirs), "$evidence.jpeg.gcm")
    }

    private fun requireOwnedFile(path: String): File {
        val candidate = File(path).canonicalFile
        val canonicalRoot = root.canonicalFile
        require(candidate.path.startsWith(canonicalRoot.path + File.separator)) { "Evidence path is outside app storage" }
        return candidate
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        const val MAX_JPEG_BYTES = 15L * 1024 * 1024
        private const val KEY_ALIAS = "rwms-worker-evidence-v1"
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'W'.code.toByte(), 'M'.code.toByte(), 'S'.code.toByte(), 1)

        fun safeUuid(value: String): String = java.util.UUID.fromString(value).toString()
    }
}
