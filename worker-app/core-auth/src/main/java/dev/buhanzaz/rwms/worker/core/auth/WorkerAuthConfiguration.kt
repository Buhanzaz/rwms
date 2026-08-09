package dev.buhanzaz.rwms.worker.core.auth

import android.net.Uri

/**
 * Owns the worker OAuth/session boundary. Credential storage stays encrypted and the gateway remains authoritative.
 */
data class WorkerAuthConfiguration(
    val publicBaseUrl: Uri,
) {
    init {
        require(publicBaseUrl.scheme == "https" && !publicBaseUrl.host.isNullOrBlank()) {
            "Only an absolute HTTPS public gateway URL is allowed"
        }
        require(
            publicBaseUrl.encodedUserInfo == null &&
                publicBaseUrl.query == null &&
                publicBaseUrl.fragment == null &&
                (publicBaseUrl.path.isNullOrEmpty() || publicBaseUrl.path == "/"),
        ) {
            "Public gateway URL must be an HTTPS origin without path, query, fragment, or user info"
        }
    }

    /**
     * The authorization server returns this URI only as an HTTP Location that
     * the native login client validates and consumes in memory. Android never
     * exposes a browser/deep-link receiver for it.
     */
    val redirectUri: Uri get() = publicBaseUrl.buildUpon()
        .appendPath("auth")
        .appendPath("worker")
        .appendPath("callback")
        .build()

    val authorizationEndpoint: Uri get() = publicBaseUrl.buildUpon().appendPath("auth").appendPath("oauth2").appendPath("authorize").build()
    val tokenEndpoint: Uri get() = publicBaseUrl.buildUpon().appendPath("auth").appendPath("oauth2").appendPath("token").build()
    val revocationEndpoint: Uri get() = publicBaseUrl.buildUpon().appendPath("auth").appendPath("oauth2").appendPath("revoke").build()
    val csrfEndpoint: Uri get() = publicBaseUrl.buildUpon().appendPath("auth").appendPath("api").appendPath("auth").appendPath("csrf").build()
    val loginEndpoint: Uri get() = publicBaseUrl.buildUpon().appendPath("auth").appendPath("login").build()
    val logoutEndpoint: Uri get() = publicBaseUrl.buildUpon().appendPath("auth").appendPath("logout").build()
}

const val WORKER_OAUTH_CLIENT_ID = "rwms-worker-android"
const val WORKER_OAUTH_SCOPE = "openid profile offline_access worker.tasks"
