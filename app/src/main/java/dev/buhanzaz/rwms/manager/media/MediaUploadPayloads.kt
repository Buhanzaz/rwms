package dev.buhanzaz.rwms.manager.media

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Locally prepared bytes accepted by one of the media upload contract shapes. */
internal sealed interface MediaUploadPayload {
    val fileName: String
}

/** Exact compatibility source body retained for video and explicit legacy source uploads. */
internal data class PhotoPayload(
    override val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
    val checksumSha256: String,
) : MediaUploadPayload {
    companion object {
        fun exactJpeg(fileName: String, bytes: ByteArray): PhotoPayload {
            require(bytes.isNotEmpty()) { "Фотография пуста" }
            return PhotoPayload(
                fileName = fileName,
                contentType = "image/jpeg",
                bytes = bytes,
                checksumSha256 = bytes.sha256(),
            )
        }
    }
}

/** Stable keys that fence one logical media create, its parts, and its finalize command. */
internal data class MediaUploadIdentity(
    val folderId: String,
    val createSessionKey: String,
    val finalizeKey: String,
    val contentUploadKey: String? = null,
    val variantUploadKeys: Map<ImageUploadVariantKind, String> = emptyMap(),
)

/** Preserves the established identity of a compatibility source upload across app upgrades. */
internal fun mediaUploadIdentity(
    owner: MediaOwner,
    localUri: String,
    photo: PhotoPayload,
    sortOrder: Int,
): MediaUploadIdentity {
    require(sortOrder >= 0) { "Порядок фотографии не может быть отрицательным" }
    val fingerprint = listOf(
        "rwms-mobile-media-upload-v1",
        owner.ownerType,
        owner.ownerId.orEmpty(),
        owner.documentId.orEmpty(),
        owner.lineId.orEmpty(),
        owner.warehouseId,
        owner.context,
        localUri,
        photo.fileName,
        photo.contentType,
        photo.bytes.size.toString(),
        photo.checksumSha256,
        sortOrder.toString(),
    ).joinToString(separator = "\u001f")
    return MediaUploadIdentity(
        folderId = stableMediaUploadUuid("folder", fingerprint),
        createSessionKey = stableMediaUploadUuid("create", fingerprint),
        finalizeKey = stableMediaUploadUuid("content-finalize", fingerprint),
        contentUploadKey = stableMediaUploadUuid("content-finalize", fingerprint),
    )
}

/** Stable identities for a logical image and every independently retried WebP part. */
internal fun imageBundleUploadIdentity(
    owner: MediaOwner,
    localUri: String,
    bundle: ImageUploadBundle,
    sortOrder: Int,
): MediaUploadIdentity {
    require(sortOrder >= 0) { "Порядок фотографии не может быть отрицательным" }
    val manifestChecksum = imageVariantManifestSha256(bundle.variants)
    val fingerprint = listOf(
        "rwms-mobile-image-upload-v2",
        owner.ownerType,
        owner.ownerId.orEmpty(),
        owner.documentId.orEmpty(),
        owner.lineId.orEmpty(),
        owner.warehouseId,
        owner.context,
        localUri,
        bundle.fileName,
        manifestChecksum,
        sortOrder.toString(),
    ).joinToString(separator = "\u001f")
    return MediaUploadIdentity(
        folderId = stableMediaUploadUuid("folder", fingerprint),
        createSessionKey = stableMediaUploadUuid("create", fingerprint),
        finalizeKey = stableMediaUploadUuid("variants-finalize", fingerprint),
        variantUploadKeys = ImageUploadVariantKind.entries.associateWith { kind ->
            stableMediaUploadUuid("variant-${kind.name}", fingerprint)
        },
    )
}

/** Digest of the exact canonical variant declaration used in a logical image identity. */
internal fun imageVariantManifestSha256(variants: List<ImageUploadVariant>): String {
    val byKind = variants.associateBy(ImageUploadVariant::kind)
    require(
        variants.size == ImageUploadVariantKind.entries.size &&
            byKind.keys == ImageUploadVariantKind.entries.toSet()
    ) {
        "Манифест изображения должен содержать три канонических варианта"
    }
    val manifest = buildString {
        append("rwms-image-variants-v1\n")
        ImageUploadVariantKind.entries.forEach { kind ->
            val variant = requireNotNull(byKind[kind])
            append(kind.name)
            append(':')
            append(variant.contentLength)
            append(':')
            append(variant.checksumSha256)
            append(':')
            append(variant.width)
            append('x')
            append(variant.height)
            append('\n')
        }
    }
    return manifest.toByteArray(StandardCharsets.UTF_8).sha256()
}

/** Prepares durable outbox originals for the image-bundle or compatibility upload contract. */
internal class PhotoPayloadReader(
    private val contentResolver: ContentResolver,
    private val imageEncoder: ImageUploadBundleEncoder = ImageUploadBundleEncoder(),
) {
    fun read(uriText: String): MediaUploadPayload {
        val uri = Uri.parse(uriText)
        val sourceFile = uri.takeIf { it.scheme == ContentResolver.SCHEME_FILE }
            ?.path
            ?.let(::File)
            ?.takeIf(File::isFile)
        val signature = when {
            sourceFile != null -> sourceFile.inputStream().use(::readMediaSignature)
            else -> contentResolver.openInputStream(uri)?.use(::readMediaSignature)
                ?: throw IllegalArgumentException("Не удалось прочитать медиафайл")
        }
        val contentType = detectMediaContentType(signature)
            ?: throw IllegalArgumentException(
                "Поддерживаются только изображения JPEG, PNG, WebP и видео MP4, WebM",
            )
        if (contentType.startsWith("image/")) {
            requireNotNull(sourceFile) {
                "Фотография должна быть скопирована в защищённую очередь перед загрузкой"
            }
            return imageEncoder.encode(sourceFile)
        }
        val bytes = when {
            sourceFile != null -> sourceFile.readBytes()
            else -> contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Не удалось прочитать медиафайл")
        }
        val fileName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
            ?: "rwms-media-${stableMediaUploadUuid("file-name", uriText)}" +
                mediaFileExtension(contentType)

        return PhotoPayload(
            fileName = fileName,
            contentType = contentType,
            bytes = bytes,
            checksumSha256 = bytes.sha256(),
        )
    }

    private fun readMediaSignature(input: java.io.InputStream): ByteArray {
        val signature = ByteArray(MEDIA_SIGNATURE_BYTES)
        var count = 0
        while (count < signature.size) {
            val read = input.read(signature, count, signature.size - count)
            if (read <= 0) break
            count += read
        }
        return if (count <= 0) byteArrayOf() else signature.copyOf(count)
    }
}

private fun stableMediaUploadUuid(scope: String, fingerprint: String): String =
    UUID.nameUUIDFromBytes(
        "rwms-mobile-media:$scope:$fingerprint".toByteArray(StandardCharsets.UTF_8),
    ).toString()

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

private fun mediaFileExtension(contentType: String): String = when (contentType) {
    "image/jpeg" -> ".jpg"
    "image/png" -> ".png"
    "image/webp" -> ".webp"
    "video/mp4" -> ".mp4"
    "video/webm" -> ".webm"
    else -> error("Unsupported canonical media content type: $contentType")
}

private const val MEDIA_SIGNATURE_BYTES = 16
