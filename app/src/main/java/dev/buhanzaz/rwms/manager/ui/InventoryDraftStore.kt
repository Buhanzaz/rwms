package dev.buhanzaz.rwms.manager.ui

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.ExplicitNullJsonAdapterFactory
import dev.buhanzaz.rwms.manager.network.InventorySessionDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.DigestInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Account-and-warehouse partition that is allowed to read or replace one inventory draft. */
internal data class InventoryDraftScope(
    val ownerAccountId: String,
    val warehouseId: String,
) {
    init {
        require(ownerAccountId.isNotBlank()) { "Не указан владелец черновика" }
        require(warehouseId.isNotBlank()) { "Не указан склад черновика" }
    }

    val storageSuffix: String
        get() = inventoryDraftStableHash("$ownerAccountId\u0000$warehouseId")
}

/** Complete process-death recovery point for the current inventory inspection. */
internal data class InventoryDraftSnapshot(
    val editor: InventoryEditorState,
    val inventorySession: InventorySessionDto,
    val route: String,
)

/** Authenticated-encryption boundary used by the inventory draft metadata store. */
internal interface InventoryDraftCipher {
    fun encrypt(plainText: String, associatedData: String): String

    fun decrypt(encoded: String, associatedData: String): String
}

/**
 * Persists one crash-safe inventory editor per verified account and warehouse.
 *
 * Metadata is AES-GCM authenticated. Media originals are copied into the same app-private files
 * partition before the encrypted pointer document is committed, so Android cache reclamation or
 * abrupt process death cannot remove a selected attachment.
 */
internal class InventoryDraftStore(
    context: Context,
    private val cipher: InventoryDraftCipher = AndroidKeystoreInventoryDraftCipher(),
) {
    private val applicationContext = context.applicationContext
    private val preferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val rootDirectory by lazy {
        File(applicationContext.filesDir, ROOT_DIRECTORY_NAME)
    }
    private val adapter: JsonAdapter<InventoryDraftDocument> by lazy {
        Moshi.Builder()
            .add(ExplicitNullJsonAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
            .adapter(InventoryDraftDocument::class.java)
            .serializeNulls()
    }
    private val mutex = Mutex()

    /** Restores only the exact verified partition and never assigns an ownerless draft. */
    suspend fun read(scope: InventoryDraftScope): InventoryDraftSnapshot? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = preferenceKey(scope)
                val encrypted = preferences.getString(key, null) ?: return@withLock null
                val document = runCatching {
                    adapter.fromJson(cipher.decrypt(encrypted, key))
                }.getOrNull() ?: return@withLock null
                if (document.schemaVersion != INVENTORY_DRAFT_SCHEMA_VERSION ||
                    document.ownerAccountId != scope.ownerAccountId ||
                    document.warehouseId != scope.warehouseId ||
                    document.inventorySession.warehouseId != scope.warehouseId
                ) {
                    return@withLock null
                }
                sanitizeRestoredSnapshot(scope, document.toSnapshot())
            }
        }

    /**
     * Copies every transient attachment first, then atomically commits the encrypted recovery
     * pointer. The returned snapshot contains only durable app-private attachment URIs.
     */
    suspend fun write(
        scope: InventoryDraftScope,
        snapshot: InventoryDraftSnapshot,
    ): InventoryDraftSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(snapshot.inventorySession.warehouseId == scope.warehouseId) {
                "Черновик не принадлежит выбранному складу"
            }
            val durable = materializeSnapshot(scope, snapshot)
            val key = preferenceKey(scope)
            val document = InventoryDraftDocument.from(scope, durable)
            val encrypted = cipher.encrypt(adapter.toJson(document), key)
            check(preferences.edit().putString(key, encrypted).commit()) {
                "Не удалось сохранить черновик инвентаризации"
            }
            removeUnreferencedMedia(scope, durable)
            durable
        }
    }

    /** Imports one camera/gallery original before it is exposed as durable editor state. */
    suspend fun importMedia(scope: InventoryDraftScope, uriText: String): String =
        withContext(Dispatchers.IO) {
            mutex.withLock { materializeMedia(scope, uriText) }
        }

    /** Removes exactly one explicitly closed or durably enqueued partition. */
    suspend fun clear(scope: InventoryDraftScope) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(preferences.edit().remove(preferenceKey(scope)).commit()) {
                    "Не удалось очистить черновик инвентаризации"
                }
                scopeDirectory(scope).deleteRecursively()
            }
        }
    }

    private fun materializeSnapshot(
        scope: InventoryDraftScope,
        snapshot: InventoryDraftSnapshot,
    ): InventoryDraftSnapshot {
        val planLineMediaIds = snapshot.editor.planLineMediaIds()
        val requiredSourceUris = buildList {
            addAll(snapshot.editor.photoUris)
            snapshot.editor.planLines.forEach { line -> addAll(line.photoUris) }
        }.distinct()
        val durableBySource = requiredSourceUris
            .associateWith { uri -> materializeMedia(scope, uri) }
            .toMutableMap()
        val planLineMediaUris = buildSet {
            snapshot.editor.persistedPhotoMedia.forEach { (uri, reference) ->
                if (reference.mediaId in planLineMediaIds) add(uri)
            }
            snapshot.editor.uploadedPhotoMedia.forEach { (uri, reference) ->
                if (reference.mediaId in planLineMediaIds) add(uri)
            }
        }
        planLineMediaUris
            .filterNot { uri -> uri in durableBySource }
            .forEach { uri ->
                runCatching { materializeMedia(scope, uri) }
                    .getOrNull()
                    ?.let { durable -> durableBySource[uri] = durable }
            }
        fun durable(uri: String): String = requireNotNull(durableBySource[uri])
        fun materializedPlanLineMedia(
            mediaByUri: Map<String, MediaReferenceDto>,
        ) = mediaByUri
            .filter { (uri, reference) ->
                reference.mediaId !in planLineMediaIds || uri in durableBySource
            }
            .mapKeys { (uri, _) -> durableBySource[uri] ?: uri }

        val editor = snapshot.editor.copy(
            photoUris = snapshot.editor.photoUris.map(::durable),
            coverPhotoUri = snapshot.editor.coverPhotoUri
                ?.takeIf(snapshot.editor.photoUris::contains)
                ?.let(durableBySource::get),
            persistedPhotoMedia = materializedPlanLineMedia(snapshot.editor.persistedPhotoMedia),
            uploadedPhotoMedia = materializedPlanLineMedia(snapshot.editor.uploadedPhotoMedia),
            planLines = snapshot.editor.planLines.map { line ->
                line.copy(photoUris = line.photoUris.map(::durable))
            },
        )
        return snapshot.copy(
            editor = editor,
            route = inventoryDraftRouteOrDefault(snapshot.route),
        )
    }

    private fun materializeMedia(scope: InventoryDraftScope, uriText: String): String {
        val sourceUri = Uri.parse(uriText)
        val mediaDirectory = mediaDirectory(scope).apply { mkdirs() }
        val sourceFile = if (sourceUri.scheme == ContentResolver.SCHEME_FILE) {
            sourceUri.path?.let(::File)
        } else {
            null
        }
        if (sourceFile?.isInside(mediaDirectory) == true && sourceFile.isFile && sourceFile.length() > 0L) {
            return Uri.fromFile(sourceFile).toString()
        }

        val extension = managerDraftMediaExtension(
            contentType = applicationContext.contentResolver.getType(sourceUri),
            path = sourceUri.path,
        )
        val temporary = File(mediaDirectory, ".${UUID.randomUUID()}.$extension.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val input = when (sourceUri.scheme) {
            ContentResolver.SCHEME_FILE -> sourceFile
                ?.takeIf(File::isFile)
                ?.inputStream()
            else -> applicationContext.contentResolver.openInputStream(sourceUri)
        } ?: throw IllegalArgumentException("Не удалось прочитать медиафайл черновика")

        try {
            DigestInputStream(input, digest).use { source ->
                FileOutputStream(temporary).use { output ->
                    source.copyTo(output)
                    output.fd.sync()
                }
            }
            require(temporary.length() > 0L) { "Выбран пустой медиафайл" }
            val target = File(
                mediaDirectory,
                "${digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }}.$extension",
            )
            if (target.isFile && target.length() == temporary.length()) {
                temporary.delete()
            } else {
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("Не удалось заменить файл черновика")
                }
                check(temporary.renameTo(target)) { "Не удалось закрепить файл черновика" }
            }
            return Uri.fromFile(target).toString()
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }

    private fun sanitizeRestoredSnapshot(
        scope: InventoryDraftScope,
        snapshot: InventoryDraftSnapshot,
    ): InventoryDraftSnapshot {
        val mediaDirectory = mediaDirectory(scope)
        fun available(uriText: String): Boolean {
            val file = Uri.parse(uriText).path?.let(::File) ?: return false
            return file.isInside(mediaDirectory) && file.isFile && file.length() > 0L
        }
        val planLineMediaIds = snapshot.editor.planLineMediaIds()
        val retained = snapshot.editor.photoUris.filter(::available)
        val retainedSet = retained.toSet()
        fun retainedPlanLineMedia(
            mediaByUri: Map<String, MediaReferenceDto>,
        ) = mediaByUri.filter { (uri, reference) ->
            uri in retainedSet ||
                (reference.mediaId in planLineMediaIds && available(uri))
        }
        val editor = snapshot.editor.copy(
            photoUris = retained,
            coverPhotoUri = snapshot.editor.coverPhotoUri?.takeIf(retainedSet::contains),
            persistedPhotoMedia = retainedPlanLineMedia(snapshot.editor.persistedPhotoMedia),
            uploadedPhotoMedia = retainedPlanLineMedia(snapshot.editor.uploadedPhotoMedia),
            planLines = snapshot.editor.planLines.map { line ->
                line.copy(photoUris = line.photoUris.filter(::available))
            },
        )
        return snapshot.copy(
            editor = editor,
            route = inventoryDraftRouteOrDefault(snapshot.route),
        )
    }

    private fun removeUnreferencedMedia(
        scope: InventoryDraftScope,
        snapshot: InventoryDraftSnapshot,
    ) {
        val planLineMediaIds = snapshot.editor.planLineMediaIds()
        val referenced = buildSet {
            snapshot.editor.photoUris.mapNotNullTo(this) { Uri.parse(it).path }
            snapshot.editor.planLines.forEach { line ->
                line.photoUris.mapNotNullTo(this) { Uri.parse(it).path }
            }
            snapshot.editor.persistedPhotoMedia
                .filterValues { reference -> reference.mediaId in planLineMediaIds }
                .keys
                .mapNotNullTo(this) { Uri.parse(it).path }
            snapshot.editor.uploadedPhotoMedia
                .filterValues { reference -> reference.mediaId in planLineMediaIds }
                .keys
                .mapNotNullTo(this) { Uri.parse(it).path }
        }
        mediaDirectory(scope).listFiles()?.forEach { file ->
            if (file.absolutePath !in referenced) file.delete()
        }
    }

    private fun preferenceKey(scope: InventoryDraftScope) =
        "inventory_draft_v${INVENTORY_DRAFT_SCHEMA_VERSION}_${scope.storageSuffix}"

    private fun scopeDirectory(scope: InventoryDraftScope) =
        File(rootDirectory, scope.storageSuffix)

    private fun mediaDirectory(scope: InventoryDraftScope) =
        File(scopeDirectory(scope), MEDIA_DIRECTORY_NAME)

    /** Local storage names and schema version. */
    private companion object {
        const val PREFERENCES_NAME = "rwms_manager_inventory_drafts"
        const val ROOT_DIRECTORY_NAME = "manager-inventory-drafts"
        const val MEDIA_DIRECTORY_NAME = "media"
    }
}

/** Encrypted wire document that binds a snapshot to its verified local storage partition. */
private data class InventoryDraftDocument(
    val schemaVersion: Int,
    val ownerAccountId: String,
    val warehouseId: String,
    val editor: InventoryEditorState,
    val inventorySession: InventorySessionDto,
    val route: String,
) {
    fun toSnapshot() = InventoryDraftSnapshot(editor, inventorySession, route)

    /** Document construction that cannot swap owner or warehouse identifiers. */
    companion object {
        fun from(scope: InventoryDraftScope, snapshot: InventoryDraftSnapshot) =
            InventoryDraftDocument(
                schemaVersion = INVENTORY_DRAFT_SCHEMA_VERSION,
                ownerAccountId = scope.ownerAccountId,
                warehouseId = scope.warehouseId,
                editor = snapshot.editor,
                inventorySession = snapshot.inventorySession,
                route = snapshot.route,
            )
    }
}

/** AES-GCM implementation backed by a non-exportable Android Keystore key. */
private class AndroidKeystoreInventoryDraftCipher : InventoryDraftCipher {
    override fun encrypt(plainText: String, associatedData: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(":")
    }

    override fun decrypt(encoded: String, associatedData: String): String {
        val parts = encoded.split(":", limit = 2)
        require(parts.size == 2) { "Malformed encrypted inventory draft" }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        return String(
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
            StandardCharsets.UTF_8,
        )
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return synchronized(KEY_CREATION_LOCK) {
            val refreshed = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (refreshed.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER,
            ).apply {
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
    }

    /** Cryptographic constants and the process-local key-creation lock. */
    private companion object {
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "rwms-manager-inventory-draft-v1"
        const val GCM_TAG_BITS = 128
        val KEY_CREATION_LOCK = Any()
    }
}

/** Keeps only supported image/video extensions in the durable draft directory. */
private fun managerDraftMediaExtension(contentType: String?, path: String?): String =
    when (contentType?.substringBefore(';')?.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        else -> when (path?.substringAfterLast('.', "")?.lowercase()) {
            "jpg", "jpeg" -> "jpg"
            "png" -> "png"
            "webp" -> "webp"
            "mp4" -> "mp4"
            "webm" -> "webm"
            else -> throw IllegalArgumentException("Неподдерживаемый формат медиафайла")
        }
    }

/** Resolves a canonical path without accepting a sibling that only shares its text prefix. */
private fun File.isInside(directory: File): Boolean = runCatching {
    val canonicalDirectory = directory.canonicalFile
    generateSequence(canonicalFile.parentFile) { parent -> parent.parentFile }
        .any { parent -> parent == canonicalDirectory }
}.getOrDefault(false)

/** Returns media that a work line owns, even though its URI is not a condition-photo URI. */
private fun InventoryEditorState.planLineMediaIds(): Set<String> = planLines
    .asSequence()
    .filter { line -> line.lineType == "WORK" }
    .flatMap { line -> line.mediaReferences.asSequence() }
    .map { reference -> reference.mediaId }
    .toSet()

private const val INVENTORY_DRAFT_SCHEMA_VERSION = 1

private val INVENTORY_DRAFT_ROUTES = setOf(
    "manager-inventory-editor",
    "manager-inventory-photos",
    "manager-inventory-furniture-decision",
    "manager-inventory-furniture",
    "manager-inventory-catalog",
    "manager-inventory-inspection-details",
    "manager-inventory-confirmation",
)

/** Rejects an obsolete or unrelated navigation target without discarding the draft itself. */
internal fun inventoryDraftRouteOrDefault(route: String): String =
    route.takeIf(INVENTORY_DRAFT_ROUTES::contains) ?: "manager-inventory-editor"

private fun inventoryDraftStableHash(value: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
