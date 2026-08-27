package dev.buhanzaz.rwms.client.auth

import com.google.common.truth.Truth.assertThat
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

/** Verifies that native-login cookies stay bounded and replace matching server cookies. */
class EphemeralCustomerCookieJarTest {
    @Test
    fun `new cookie value replaces matching cookie without duplication`() {
        val jar = EphemeralCustomerCookieJar()
        val url = "https://example.test/login".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("SESSION", "first")))

        jar.saveFromResponse(url, listOf(cookie("SESSION", "second")))

        assertThat(jar.loadForRequest(url).map { it.value }).containsExactly("second")
    }

    private fun cookie(name: String, value: String): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain("example.test")
        .path("/")
        .build()
}
