package dev.buhanzaz.rwms.client.data

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

/** Locks CustomerApp profile and avatar payloads to their public logistics/media contracts. */
class CustomerProfileContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `profile response decodes exact protected avatar generation`() {
        val profile = json.decodeFromString<CustomerProfile>(
            """{"id":"profile-a","version":4,"entityType":"INDIVIDUAL","firstName":"Иван",
              "lastName":"Петров","companyName":null,"phone":"+79990000000","email":null,
              "additionalInfo":null,"avatar":{"mediaId":"media-a","generation":3,
              "warehouseId":"warehouse-spb","thumbnailUrl":"/api/media/v1/small",
              "url":"/api/media/v1/large"}}""",
        )

        assertThat(profile.avatar?.mediaId).isEqualTo("media-a")
        assertThat(profile.avatar?.generation).isEqualTo(3)
        assertThat(profile.avatar?.warehouseId).isEqualTo("warehouse-spb")
    }

    @Test
    fun `profile owner upload never serializes structured shipment identity`() {
        val payload = json.encodeToString(
            CreateCustomerMediaUploadRequest(
                ownerType = "LOGISTICS_CUSTOMER_PROFILE",
                ownerId = "profile-a",
                warehouseId = "warehouse-spb",
                context = "PROFILE_AVATAR",
                folderId = "folder-a",
                fileName = "avatar.jpg",
                contentType = "image/jpeg",
                contentLength = 42,
                checksumSha256 = "a".repeat(64),
                sortOrder = 0,
            ),
        )

        assertThat(payload).contains("\"ownerId\":\"profile-a\"")
        assertThat(payload).doesNotContain("documentId")
        assertThat(payload).doesNotContain("lineId")
    }

    @Test
    fun `profile update carries optimistic version without mutable entity kind`() {
        val payload = json.encodeToString(
            UpdateCustomerProfileRequest(
                expectedVersion = 7,
                firstName = "Пётр",
                lastName = "Петров",
                phone = "+79990000000",
            ),
        )

        assertThat(payload).contains("\"expectedVersion\":7")
        assertThat(payload).doesNotContain("entityType")
    }
}
