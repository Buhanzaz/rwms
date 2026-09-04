package dev.buhanzaz.rwms.worker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import dev.buhanzaz.rwms.worker.core.network.CreateUploadSessionRequestDto
import dev.buhanzaz.rwms.worker.core.network.FinalizeUploadRequestDto
import dev.buhanzaz.rwms.worker.core.network.MediaAssetDto
import dev.buhanzaz.rwms.worker.core.network.WorkerGatewayClient
import dev.buhanzaz.rwms.worker.core.network.safeWorkerUserMessage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody

/** Read-only worker group shown on the server-backed profile screen. */
data class ProfileGroupUi(
    val id: String,
    val name: String,
    val workerClassName: String,
)

/** Profile state combines the offline identity projection with server-owned avatar media. */
data class ProfileUiState(
    val displayName: String? = null,
    val login: String? = null,
    val groups: List<ProfileGroupUi> = emptyList(),
    val qualifications: List<String> = emptyList(),
    val currentGroupId: String? = null,
    val currentGroupName: String? = null,
    val operationalAvailability: String = "AVAILABLE",
    val avatar: Bitmap? = null,
    val isAvatarLoading: Boolean = false,
    val isAvatarUploading: Boolean = false,
    val avatarError: String? = null,
)

private data class LocalProfile(
    val displayName: String? = null,
    val login: String? = null,
    val groups: List<ProfileGroupUi> = emptyList(),
    val currentGroupId: String? = null,
    val currentGroupName: String? = null,
    val operationalAvailability: String = "AVAILABLE",
)

private data class RemoteProfile(
    val displayName: String,
    val login: String,
    val groups: List<ProfileGroupUi>,
    val qualifications: List<String>,
    val currentGroupId: String?,
    val currentGroupName: String?,
    val operationalAvailability: String,
)

private data class AvatarState(
    val bitmap: Bitmap? = null,
    val loading: Boolean = false,
    val uploading: Boolean = false,
    val error: String? = null,
)

/** Loads and replaces only the authenticated worker's canonical profile avatar. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel @Inject constructor(
    private val localStore: WorkerLocalStore,
    private val gateway: WorkerGatewayClient,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val userId = MutableStateFlow<String?>(null)
    private val remote = MutableStateFlow<RemoteProfile?>(null)
    private val avatar = MutableStateFlow(AvatarState())
    private var refreshJob: Job? = null

    private val localProfile = userId.flatMapLatest { id ->
        if (id == null) {
            flowOf(LocalProfile())
        } else {
            combine(
                localStore.observeSession(id),
                localStore.observeGroups(id),
            ) { session, groups ->
                LocalProfile(
                    displayName = session?.displayName,
                    login = session?.login,
                    groups = groups.map { group ->
                        ProfileGroupUi(group.groupId, group.name, group.workerClassName)
                    },
                    currentGroupId = session?.currentGroupId,
                    currentGroupName = session?.currentGroupName,
                    operationalAvailability = session?.operationalAvailability ?: "AVAILABLE",
                )
            }
        }
    }

    val state: StateFlow<ProfileUiState> = combine(localProfile, remote, avatar) {
            local,
            server,
            avatarState,
        ->
        ProfileUiState(
            displayName = server?.displayName ?: local.displayName,
            login = server?.login ?: local.login,
            groups = server?.groups ?: local.groups,
            qualifications = server?.qualifications
                ?: local.groups.map(ProfileGroupUi::workerClassName).filter(String::isNotBlank).distinct(),
            currentGroupId = server?.currentGroupId ?: local.currentGroupId,
            currentGroupName = server?.currentGroupName ?: local.currentGroupName,
            operationalAvailability = server?.operationalAvailability ?: local.operationalAvailability,
            avatar = avatarState.bitmap,
            isAvatarLoading = avatarState.loading,
            isAvatarUploading = avatarState.uploading,
            avatarError = avatarState.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    fun bind(userId: String) {
        if (this.userId.value == userId) return
        this.userId.value = userId
        remote.value = null
        avatar.value = AvatarState(loading = true)
        refresh()
    }

    fun refresh() {
        if (userId.value == null) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            avatar.value = avatar.value.copy(loading = true, error = null)
            runCatching {
                val workerContext = gateway.context()
                remote.value = RemoteProfile(
                    displayName = workerContext.worker.displayName,
                    login = workerContext.worker.login,
                    groups = workerContext.groups.map { group ->
                        ProfileGroupUi(group.id, group.name, group.workerClassName)
                    },
                    qualifications = workerContext.qualifications.map { it.name }.distinct(),
                    currentGroupId = workerContext.currentGroup?.id,
                    currentGroupName = workerContext.currentGroup?.name,
                    operationalAvailability = workerContext.operationalAvailability,
                )
                val scope = gateway.prepareWorkerProfileAvatarScope()
                val ready = newestReadyAvatar(gateway.mediaAssets(scope).items)
                val bitmap = ready?.let { loadAvatar(it) }
                avatar.value = AvatarState(bitmap = bitmap)
            }.onFailure { failure ->
                avatar.value = avatar.value.copy(
                    loading = false,
                    error = failure.safeWorkerUserMessage("Не удалось загрузить аватар"),
                )
            }
        }
    }

    fun uploadAvatar(bitmap: Bitmap, onSuccess: () -> Unit) {
        if (avatar.value.uploading) return
        viewModelScope.launch {
            avatar.value = avatar.value.copy(uploading = true, error = null)
            runCatching {
                val ready = withContext(Dispatchers.IO) { uploadAvatar(bitmap) }
                val displayed = loadAvatar(ready)
                    ?: throw IllegalStateException("Ready avatar has no readable image variant")
                avatar.value = AvatarState(bitmap = displayed)
                onSuccess()
            }.onFailure { failure ->
                avatar.value = avatar.value.copy(
                    uploading = false,
                    error = failure.safeWorkerUserMessage("Не удалось сохранить аватар"),
                )
            }
        }
    }

    fun dismissAvatarError() {
        avatar.value = avatar.value.copy(error = null)
    }

    private suspend fun uploadAvatar(bitmap: Bitmap): MediaAssetDto {
        val source = File.createTempFile("worker-avatar-", ".jpg", context.cacheDir)
        try {
            FileOutputStream(source).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
            }
            check(source.length() in 1..MAX_AVATAR_BYTES) { "Prepared avatar has an invalid size" }
            val scope = gateway.prepareWorkerProfileAvatarScope()
            val checksum = source.sha256()
            val operationId = UUID.nameUUIDFromBytes(
                "${scope.ownerId}:profile-avatar:$checksum".toByteArray(),
            ).toString()
            val folderId = UUID.nameUUIDFromBytes(
                "profile-avatar:${scope.ownerId}".toByteArray(),
            ).toString()
            val session = gateway.createUploadSession(
                operationId,
                CreateUploadSessionRequestDto(
                    ownerType = scope.ownerType,
                    ownerId = scope.ownerId,
                    warehouseId = scope.warehouseId,
                    context = scope.context,
                    folderId = folderId,
                    fileName = "avatar.jpg",
                    contentType = AVATAR_CONTENT_TYPE,
                    contentLength = source.length(),
                    checksumSha256 = checksum,
                ),
            )
            val contentPath = session.contentUploadUrl
                ?: throw IllegalStateException("Media-service returned no avatar upload path")
            val uploaded = gateway.uploadMediaContent(
                sameOriginContentPath = contentPath,
                idempotencyKey = operationId,
                content = source.asRequestBody(AVATAR_CONTENT_TYPE.toMediaType()),
            )
            var asset = gateway.finalizeUploadSession(
                uploadSessionId = session.uploadSessionId,
                idempotencyKey = operationId,
                request = FinalizeUploadRequestDto(
                    objectVersionId = uploaded.objectVersionId,
                    etag = uploaded.etag,
                    checksumSha256 = uploaded.checksumSha256,
                ),
            )
            repeat(AVATAR_READY_POLL_ATTEMPTS) {
                when (asset.status) {
                    "READY" -> return asset
                    "FAILED" -> throw IllegalStateException("Avatar processing failed")
                }
                delay(AVATAR_READY_POLL_DELAY_MILLIS)
                asset = gateway.mediaAssets(scope).items.firstOrNull { it.id == session.mediaId }
                    ?: throw IllegalStateException("Uploaded avatar disappeared")
            }
            throw IllegalStateException("Avatar processing timed out")
        } finally {
            source.delete()
        }
    }

    private suspend fun loadAvatar(asset: MediaAssetDto): Bitmap? = withContext(Dispatchers.IO) {
        val path = asset.variants.firstOrNull { it.kind == "SMALL" }?.contentPath
            ?: asset.variants.firstOrNull { it.kind == "MEDIUM" }?.contentPath
            ?: asset.variants.firstOrNull { it.kind == "LARGE" }?.contentPath
            ?: return@withContext null
        gateway.mediaContent(path).use { body ->
            val length = body.contentLength()
            check(length < 0 || length <= MAX_AVATAR_BYTES) { "Avatar response is too large" }
            BitmapFactory.decodeStream(body.byteStream())
        }
    }
}

private fun newestReadyAvatar(items: List<MediaAssetDto>): MediaAssetDto? =
    items.asSequence()
        .filter { it.status == "READY" && it.kind == "IMAGE" }
        .maxByOrNull(MediaAssetDto::createdAt)

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val AVATAR_CONTENT_TYPE = "image/jpeg"
private const val MAX_AVATAR_BYTES = 10L * 1024L * 1024L
private const val AVATAR_READY_POLL_ATTEMPTS = 45
private const val AVATAR_READY_POLL_DELAY_MILLIS = 1_000L
