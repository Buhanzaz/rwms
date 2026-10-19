package dev.buhanzaz.rwms.worker.core.media

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.buhanzaz.rwms.worker.core.database.EncryptedEvidenceVariantPart
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

/** One plaintext WebP variant produced before it is durably encrypted. */
data class PlainEvidenceVariantPart(
    val kind: String,
    val file: File,
    val contentLength: Long,
    val checksumSha256: String,
    val width: Int,
    val height: Int,
)

/** A complete plaintext evidence bundle whose files remain temporary and app-private. */
data class PlainEvidenceBundle(
    val original: File,
    val variants: List<PlainEvidenceVariantPart>,
    val aggregateContentLength: Long,
    val manifestSha256: String,
)

/** Durable encrypted original plus resumable upload metadata for its three WebP variants. */
data class EncryptedEvidenceBundle(
    val originalEncryptedPath: String,
    val variants: List<EncryptedEvidenceVariantPart>,
    val aggregateContentLength: Long,
    val manifestSha256: String,
)

/**
 * Encrypts the user-visible original and every upload variant before evidence becomes durable.
 * Plain camera/gallery files are temporary; encrypted files remain until task-board confirms READY.
 */
@Singleton
class EncryptedEvidenceFileStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val root: File by lazy {
        File(context.noBackupFilesDir, "worker-evidence").also { directory ->
            check(directory.mkdirs() || directory.isDirectory) { "Could not create evidence storage" }
        }
    }

    /**
     * Encrypts a complete WebP bundle and removes every plaintext input on all outcomes. A partial
     * bundle is rolled back so Room can never reference only a subset of upload parts.
     */
    fun persistWebpBundle(
        userId: String,
        evidenceId: String,
        bundle: PlainEvidenceBundle,
    ): EncryptedEvidenceBundle {
        require(bundle.variants.map(PlainEvidenceVariantPart::kind) == REQUIRED_VARIANT_KINDS) {
            "Evidence bundle must contain SMALL, MEDIUM and LARGE variants"
        }
        require(bundle.aggregateContentLength == bundle.variants.sumOf { it.contentLength }) {
            "Evidence aggregate size does not match its variants"
        }
        require(bundle.aggregateContentLength in 1..MAX_UPLOAD_BUNDLE_BYTES) {
            "Evidence variants exceed 1 MiB"
        }
        val directory = evidenceDirectory(userId, evidenceId)
        val encryptedPaths = mutableListOf<String>()
        try {
            val originalDestination = File(directory, "original.webp.gcm")
            encryptFile(bundle.original, originalDestination)
            encryptedPaths += originalDestination.absolutePath
            val variants = bundle.variants.map { variant ->
                val destination = File(directory, "${variant.kind.lowercase()}.webp.gcm")
                encryptFile(variant.file, destination)
                encryptedPaths += destination.absolutePath
                EncryptedEvidenceVariantPart(
                    kind = variant.kind,
                    encryptedPath = destination.absolutePath,
                    contentLength = variant.contentLength,
                    checksumSha256 = variant.checksumSha256,
                    width = variant.width,
                    height = variant.height,
                )
            }
            return EncryptedEvidenceBundle(
                originalEncryptedPath = originalDestination.absolutePath,
                variants = variants,
                aggregateContentLength = bundle.aggregateContentLength,
                manifestSha256 = bundle.manifestSha256,
            )
        } catch (error: Throwable) {
            encryptedPaths.forEach { path -> runCatching { requireOwnedFile(path).delete() } }
            directory.delete()
            throw error
        } finally {
            bundle.original.delete()
            bundle.variants.forEach { variant -> variant.file.delete() }
        }
    }

    /** Opens one authenticated plaintext stream without copying the whole WebP into memory. */
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

    /**
     * Idempotently removes the encrypted original and all parts after authoritative READY. Missing
     * files are accepted so a process death between deletion and the Room transition can recover.
     */
    fun deleteBundle(originalEncryptedPath: String, variants: List<EncryptedEvidenceVariantPart>) {
        (listOf(originalEncryptedPath) + variants.map(EncryptedEvidenceVariantPart::encryptedPath))
            .forEach { path ->
                val file = requireOwnedFile(path)
                if (file.exists()) check(file.delete()) { "Could not delete uploaded evidence" }
            }
        requireOwnedFile(originalEncryptedPath).parentFile?.delete()
    }

    private fun encryptFile(source: File, destination: File) {
        require(source.isFile && source.length() > 0L) { "Prepared WebP is missing" }
        val parent = requireNotNull(destination.parentFile) { "Evidence file has no parent" }
        check(parent.mkdirs() || parent.isDirectory) { "Could not create evidence directory" }
        val partial = File(parent, "${destination.name}.partial")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
            DataOutputStream(BufferedOutputStream(FileOutputStream(partial))).use { header ->
                header.write(MAGIC)
                header.writeInt(cipher.iv.size)
                header.write(cipher.iv)
                CipherOutputStream(header, cipher).use { encrypted ->
                    BufferedInputStream(FileInputStream(source)).use { input -> input.copyTo(encrypted) }
                }
            }
            check(partial.renameTo(destination)) { "Could not atomically persist encrypted evidence" }
        } finally {
            partial.takeIf(File::exists)?.delete()
        }
    }

    private fun evidenceDirectory(userId: String, evidenceId: String): File {
        val user = safeUuid(userId)
        val evidence = safeUuid(evidenceId)
        return File(File(root, user).also(File::mkdirs), evidence)
    }

    private fun requireOwnedFile(path: String): File {
        val candidate = File(path).canonicalFile
        val canonicalRoot = root.canonicalFile
        require(candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            "Evidence path is outside app storage"
        }
        return candidate
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    /** Shared evidence limits and canonical upload-part order. */
    companion object {
        const val MAX_SOURCE_IMAGE_BYTES = 15L * 1024 * 1024
        const val MAX_UPLOAD_BUNDLE_BYTES = 1024L * 1024
        val REQUIRED_VARIANT_KINDS = listOf("SMALL", "MEDIUM", "LARGE")

        private const val KEY_ALIAS = "rwms-worker-evidence-v1"
        private val MAGIC = byteArrayOf(
            'R'.code.toByte(),
            'W'.code.toByte(),
            'M'.code.toByte(),
            'S'.code.toByte(),
            1,
        )

        fun safeUuid(value: String): String = java.util.UUID.fromString(value).toString()

        /** Returns a lowercase SHA-256 digest without retaining the input in memory. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }
    }
}

/** Renders a digest as the canonical lowercase wire representation. */
internal fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
