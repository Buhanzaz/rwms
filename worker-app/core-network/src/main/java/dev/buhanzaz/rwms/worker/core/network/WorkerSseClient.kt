package dev.buhanzaz.rwms.worker.core.network

import java.util.concurrent.TimeUnit
import java.net.URI
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * SSE carries invalidations only. Snapshot data always comes from authenticated
 * REST reads, so a dropped event can safely fall back to the foreground poll.
 */
class WorkerSseClient(
    private val client: OkHttpClient,
    private val publicBaseUrl: HttpUrl,
    private val json: Json,
) {
    /**
     * Opens an invalidation-only stream and forwards the last local event ID when one exists.
     * Consumers still refresh through REST: reconnect is not proof that the server replayed data.
     */
    fun events(lastEventId: String?): Flow<WorkerInvalidationEventDto> = callbackFlow {
        val endpoint = publicBaseUrl.newBuilder()
            .addPathSegments("api/task-board/worker/v1/events")
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "text/event-stream")
            .apply { if (!lastEventId.isNullOrBlank()) header("Last-Event-ID", lastEventId) }
            .build()
        val eventSource = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching { json.decodeFromString<WorkerInvalidationEventDto>(data) }
                    .onSuccess { trySend(it) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: GatewayUnavailableException())
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        })
        awaitClose { eventSource.cancel() }
    }
}

fun requireSameOriginApiPath(path: String): String {
    require(UPLOAD_CONTENT_PATH.matches(path)) {
        "Media content path must be the exact same-origin upload-session route"
    }
    return path
}

/** Accepts only documented same-origin original/derived asset reads. */
fun requireSameOriginMediaReadPath(path: String): String {
    val uri = runCatching { URI(path) }.getOrElse { throw IllegalArgumentException("Invalid media path", it) }
    require(uri.scheme == null && uri.rawAuthority == null && uri.rawFragment == null) {
        "Media read path must not contain an origin or fragment"
    }
    require(MEDIA_READ_PATH.matches(uri.rawPath.orEmpty())) { "Unexpected public media read route" }
    val query = uri.rawQuery
    require(query == null || SAFE_MEDIA_QUERY.matches(query)) { "Unexpected media read query" }
    return path
}

private val UPLOAD_CONTENT_PATH = Regex(
    "^/api/media/v1/upload-sessions/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/variants/(?:SMALL|MEDIUM|LARGE)/content$",
)
private val MEDIA_READ_PATH = Regex(
    "^/api/media/v1/assets/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(?:original|variants/(?:SMALL|MEDIUM|LARGE)/content)$",
)
private val SAFE_MEDIA_QUERY = Regex("^[A-Za-z0-9_.~%=&-]{1,1024}$")

fun foregroundPollIntervalMillis(): Long = TimeUnit.SECONDS.toMillis(15)
