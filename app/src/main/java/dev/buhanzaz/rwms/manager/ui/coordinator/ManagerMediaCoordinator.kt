package dev.buhanzaz.rwms.manager.ui

import android.util.Log
import dev.buhanzaz.rwms.manager.media.MediaDownloader
import dev.buhanzaz.rwms.manager.media.retryMediaReadAfterOwnerProof
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.MediaAssetDto
import dev.buhanzaz.rwms.manager.network.MediaReferenceDto
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.uploads.readyOwnerMediaAssetsById
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Owner-scoped media descriptor. A media reference is resolved only through the domain owner and
 * context that granted the manager access to it.
 */
internal data class MaintenanceMediaScope(
    val ownerType: String,
    val ownerId: String,
    val context: String,
)

/** A media reference together with the owner scopes that can prove access to it. */
internal data class ScopedMediaDownload(
    val reference: MediaReferenceDto,
    val scopes: List<MaintenanceMediaScope>,
)

/** Local cache URI and the current server reference that produced it. */
internal data class ScopedMediaResult(
    val reference: MediaReferenceDto,
    val uri: String,
)

/**
 * Owner-scoped media lookup port used by workflows that render server-owned attachments.
 *
 * <p>The port deliberately contains no upload operations or UI state. Callers provide the domain
 * owner scope that authorizes the read, while this coordinator only resolves the resulting local
 * URI.
 */
internal interface ManagerMediaPort {
    suspend fun loadInventoryPhotoUris(
        finding: InventoryFindingDto,
        warehouseId: String,
    ): List<ScopedMediaResult>

    suspend fun loadMaintenancePhotoUris(
        references: List<MediaReferenceDto>,
        scopes: List<MaintenanceMediaScope>,
        warehouseId: String,
    ): Map<String, String>

    suspend fun loadScopedPhotoUris(
        requests: List<ScopedMediaDownload>,
        warehouseId: String,
        preferCurrentOwnerReference: Boolean = false,
    ): List<ScopedMediaResult>
}

/** Internal result while a scoped media read still retains its owner-reference ordering. */
private data class ScopedDownloadedPhoto(
    val reference: MediaReferenceDto,
    val uri: String,
)

/**
 * Resolves and downloads media through owner-scoped public API calls. It bounds both owner-proof
 * lookups and content downloads so an editor with historical photos cannot monopolize a weak
 * mobile connection.
 */
internal class ManagerMediaCoordinator(
    private val backend: RwmsBackend,
    private val mediaDownloader: MediaDownloader,
) : ManagerMediaPort {
    override suspend fun loadInventoryPhotoUris(
        finding: InventoryFindingDto,
        warehouseId: String,
    ): List<ScopedMediaResult> = loadScopedPhotoUris(
        requests = finding.media
            .distinctBy(MediaReferenceDto::mediaId)
            .map { reference ->
                ScopedMediaDownload(
                    reference = reference,
                    scopes = listOf(
                        MaintenanceMediaScope(
                            ownerType = "INVENTORY_FINDING",
                            ownerId = finding.id,
                            context = "INSPECTION",
                        ),
                    ),
                )
            },
        warehouseId = warehouseId,
        preferCurrentOwnerReference = true,
    )

    override suspend fun loadMaintenancePhotoUris(
        references: List<MediaReferenceDto>,
        scopes: List<MaintenanceMediaScope>,
        warehouseId: String,
    ): Map<String, String> = loadScopedPhotoUris(
        requests = references.distinctBy(MediaReferenceDto::mediaId).map { reference ->
            ScopedMediaDownload(reference = reference, scopes = scopes)
        },
        warehouseId = warehouseId,
        preferCurrentOwnerReference = false,
    ).associate { result -> result.reference.mediaId to result.uri }

    override suspend fun loadScopedPhotoUris(
        requests: List<ScopedMediaDownload>,
        warehouseId: String,
        preferCurrentOwnerReference: Boolean,
    ): List<ScopedMediaResult> {
        if (requests.isEmpty()) return emptyList()
        val allScopes = requests.flatMap(ScopedMediaDownload::scopes).distinct()
        val assetsByScope = mapInBoundedBatches(
            values = allScopes,
            parallelism = MANAGER_OWNER_PROOF_PARALLELISM,
        ) { scope ->
            val assets = try {
                retryMediaReadAfterOwnerProof {
                    backend.api.ownerMedia(
                        ownerType = scope.ownerType,
                        ownerId = scope.ownerId,
                        warehouseId = warehouseId,
                        context = scope.context,
                    )
                }.items
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.w(
                    MANAGER_MEDIA_LOG_TAG,
                    "Owner proof failed for ${scope.ownerType}:${scope.ownerId}",
                    failure,
                )
                null
            }
            scope to assets
        }.toMap()
        // Do not start every historical original/preview at once. A supplement can contain many
        // photos; a small bound avoids saturating a weak mobile connection and turning a single
        // transient failure into a partially loaded editor.
        val downloadPermits = Semaphore(MANAGER_PHOTO_DOWNLOAD_PARALLELISM)
        return coroutineScope {
            requests.map { request ->
                async {
                    val downloaded = downloadPermits.withPermit {
                        downloadScopedPhoto(
                            request = request,
                            assetsByScope = assetsByScope,
                            warehouseId = warehouseId,
                            preferCurrentOwnerReference = preferCurrentOwnerReference,
                        )
                    } ?: return@async null
                    ScopedMediaResult(
                        reference = downloaded.reference,
                        uri = downloaded.uri,
                    )
                }
            }.mapNotNull { download -> download.await() }
        }
    }

    private suspend fun downloadScopedPhoto(
        request: ScopedMediaDownload,
        assetsByScope: Map<MaintenanceMediaScope, List<MediaAssetDto>?>,
        warehouseId: String,
        preferCurrentOwnerReference: Boolean,
    ): ScopedDownloadedPhoto? {
        request.scopes.distinct().forEach { scope ->
            val ownerAssets = assetsByScope[scope]
            val asset = if (preferCurrentOwnerReference) {
                ownerAssets
                    ?.let(::readyOwnerMediaAssetsById)
                    ?.get(request.reference.mediaId)
            } else {
                ownerAssets?.firstOrNull { candidate ->
                    candidate.id == request.reference.mediaId &&
                        candidate.generation == request.reference.generation &&
                        candidate.status == "READY"
                }
            }
            if (preferCurrentOwnerReference && ownerAssets != null && asset == null) {
                // The owner projection is authoritative for an editable inventory photo.  Do not
                // display an obsolete generation as though it were still an active attachment.
                return@forEach
            }
            val currentReference = asset?.let { candidate ->
                MediaReferenceDto(candidate.id, candidate.generation)
            } ?: request.reference
            val preview = if (asset?.kind == "VIDEO") {
                asset.variants.firstOrNull { variant -> variant.kind == "PLAYBACK" }
            } else {
                asset?.variants
                    ?.sortedBy { variant ->
                        when (variant.kind) {
                            "SMALL" -> 0
                            "MEDIUM" -> 1
                            else -> 2
                        }
                    }
                    ?.firstOrNull()
            }
            if (preview != null) {
                try {
                    mediaDownloader.downloadVariant(
                        mediaId = currentReference.mediaId,
                        generation = currentReference.generation,
                        contentPath = preview.contentPath,
                    )
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    Log.w(
                        MANAGER_MEDIA_LOG_TAG,
                        "Variant cache failed for ${currentReference.mediaId} via ${scope.ownerType}",
                        failure,
                    )
                    null
                }?.let { uri ->
                    return ScopedDownloadedPhoto(
                        reference = currentReference,
                        uri = uri,
                    )
                }
            }
            try {
                mediaDownloader.downloadOriginal(
                    mediaId = currentReference.mediaId,
                    generation = currentReference.generation,
                    ownerType = scope.ownerType,
                    ownerId = scope.ownerId,
                    warehouseId = warehouseId,
                    context = scope.context,
                    expectedContentType = asset?.contentType,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.w(
                    MANAGER_MEDIA_LOG_TAG,
                    "Original cache failed for ${currentReference.mediaId} via ${scope.ownerType}",
                    failure,
                )
                null
            }?.let { uri ->
                return ScopedDownloadedPhoto(
                    reference = currentReference,
                    uri = uri,
                )
            }
        }
        return null
    }
}

private const val MANAGER_PHOTO_DOWNLOAD_PARALLELISM = 3
private const val MANAGER_OWNER_PROOF_PARALLELISM = 4
private const val MANAGER_MEDIA_LOG_TAG = "ManagerMedia"
