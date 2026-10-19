package dev.buhanzaz.rwms.driver.core.auth

import android.net.Uri

/**
 * Owns the driver OAuth/session boundary. Credential storage stays encrypted and the gateway remains authoritative.
 */
data class DriverAuthConfiguration(
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
     * HTTPS callback consumed in memory by the native login client. DriverApp
     * deliberately exposes no Android deep-link receiver for OAuth state.
     */
    val redirectUri: Uri get() = publicBaseUrl.buildUpon()
        .appendPath("auth")
        .appendPath("driver")
        .appendPath("callback")
        .build()

    val authorizationEndpoint: Uri get() = publicBaseUrl.buildUpon().appendPath("auth").appendPath("oauth2").appendPath("authorize").build()
    val tokenEndpoint: Uri get() = publicBaseUrl.buildUpon().appendPath("auth").appendPath("oauth2").appendPath("token").build()
    val revocationEndpoint: Uri get() = publicBaseUrl.buildUpon().appendPath("auth").appendPath("oauth2").appendPath("revoke").build()
    val csrfEndpoint: Uri get() = publicBaseUrl.buildUpon().appendPath("auth").appendPath("api").appendPath("auth").appendPath("csrf").build()
    val loginEndpoint: Uri get() = publicBaseUrl.buildUpon().appendPath("auth").appendPath("login").build()
    val logoutEndpoint: Uri get() = publicBaseUrl.buildUpon().appendPath("auth").appendPath("logout").build()
}

const val DRIVER_OAUTH_CLIENT_ID = "rwms-driver-android"
const val DRIVER_OAUTH_SCOPE = "openid profile offline_access driver.tasks"
