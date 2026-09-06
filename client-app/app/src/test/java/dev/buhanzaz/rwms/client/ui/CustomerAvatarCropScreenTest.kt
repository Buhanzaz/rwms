package dev.buhanzaz.rwms.client.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises explicit export, cancellation and an unreadable source in the actual local editor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [CustomerAvatarImageSourceShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomerAvatarCropScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private val files = mutableListOf<File>()

    @After
    fun removeTestImages() {
        files.forEach(File::delete)
    }

    @Test
    fun `only Done exports one JPEG and blocks duplicate submission`() {
        val uri = imageUri()
        val exported = mutableListOf<ByteArray>()
        compose.setContent {
            CustomerTheme { CustomerAvatarCropScreen(uri, onCancel = {}, onConfirm = exported::add) }
        }
        awaitPreview()
        compose.onNodeWithTag("avatar-crop-done").assertIsEnabled().performClick()
        compose.onNodeWithTag("avatar-crop-done").assertIsNotEnabled().performClick()
        compose.waitUntil(10_000) { exported.isNotEmpty() }
        compose.runOnIdle {
            assertThat(exported).hasSize(1)
            val jpeg = exported.single()
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            assertThat(bitmap.width).isEqualTo(1024)
            assertThat(bitmap.height).isEqualTo(1024)
            bitmap.recycle()
        }
    }

    @Test
    fun `back cancels without submitting the selected image`() {
        val uri = imageUri()
        var cancelled = false
        var submits = 0
        compose.setContent {
            CustomerTheme { CustomerAvatarCropScreen(uri, onCancel = { cancelled = true }, onConfirm = { submits++ }) }
        }
        awaitPreview()
        compose.onNodeWithTag("header-back").performClick()
        compose.runOnIdle {
            assertThat(cancelled).isTrue()
            assertThat(submits).isEqualTo(0)
        }
    }

    @Test
    fun `unreadable image shows the real failure and keeps export unavailable`() {
        var submits = 0
        compose.setContent {
            CustomerTheme {
                CustomerAvatarCropScreen(Uri.parse("file:///missing-avatar.png"), onCancel = {}, onConfirm = { submits++ })
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("avatar-crop-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("avatar-crop-done").assertIsNotEnabled()
        compose.runOnIdle { assertThat(submits).isEqualTo(0) }
    }

    @Test
    fun `system picker opens local editor and cart returns only after cancelling`() {
        val uri = imageUri()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        var uploads = 0
        val workflow = CustomerWorkflowState(
            bootstrapping = false,
            profile = CustomerProfile(
                id = "profile-a", version = 7, entityType = CustomerEntityType.INDIVIDUAL,
                firstName = "Иван", lastName = "Петров", phone = "+79990000000",
            ),
            selectedWarehouse = CustomerWarehouse(
                id = "warehouse-a", name = "СПБ", timezone = "Europe/Moscow", city = "Санкт-Петербург",
                depotLatitude = 59.76, depotLongitude = 30.47,
            ),
            inquiryId = "inquiry-a",
            selectedCabinIds = setOf("cabin-a"),
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalCustomerStoreVideoBackgroundEnabled provides false,
            ) {
                CustomerTheme {
                    CustomerAppContent(CustomerAppState.Ready(workflow), onAvatarCropped = { uploads++ })
                }
            }
        }
        compose.onNodeWithTag("profile-avatar").performClick()
        compose.onNodeWithTag("cart-fab").assertDoesNotExist()
        compose.onNodeWithTag("profile-avatar-picker").performClick()
        awaitPreview()
        compose.onNodeWithTag("cart-fab").assertDoesNotExist()
        compose.onNodeWithTag("header-back").performClick()
        compose.onNodeWithTag("profile-screen").assertExists()
        compose.onNodeWithTag("cart-fab").assertDoesNotExist()
        compose.runOnIdle { assertThat(uploads).isEqualTo(0) }
    }

    private fun awaitPreview() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("avatar-crop-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun imageUri(): Uri {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File.createTempFile("avatar-editor-", ".png", context.cacheDir).also(files::add)
        val image = twoColorAvatar()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        return Uri.fromFile(file)
    }
}
