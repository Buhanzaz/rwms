package dev.buhanzaz.rwms.client.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerCart
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Native Compose render review at a small-phone size.
 *
 * Set RWMS_CUSTOMER_VISUAL_OUTPUT to export the inspected PNGs outside the repository. The welcome
 * video is deliberately disabled because Media3 video playback is not part of this JVM visual review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomerVisualReviewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `launcher artwork fills its mask and preserves both complete glyphs`() {
        val context = composeRule.activity
        val icon = context.applicationInfo.loadIcon(context.packageManager)
        assertThat(icon).isInstanceOf(AdaptiveIconDrawable::class.java)
        val adaptive = icon as AdaptiveIconDrawable
        adaptive.setBounds(0, 0, 192, 192)
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        adaptive.draw(Canvas(bitmap))
        listOf(4 to 96, 187 to 96, 96 to 4, 96 to 187).forEach { (x, y) ->
            assertThat(android.graphics.Color.alpha(bitmap.getPixel(x, y))).isEqualTo(255)
        }
        val foreground = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        try {
            adaptive.foreground.draw(Canvas(foreground))
            val foregroundPixels = IntArray(192 * 192)
            val maskedPixels = IntArray(192 * 192)
            foreground.getPixels(foregroundPixels, 0, 192, 0, 0, 192, 192)
            bitmap.getPixels(maskedPixels, 0, 192, 0, 0, 192, 192)
            listOf(0xFF549AC5.toInt(), 0xFF204B79.toInt()).forEach { color ->
                val completeGlyphPixels = foregroundPixels.count { it == color }
                assertThat(completeGlyphPixels).isGreaterThan(500)
                assertThat(maskedPixels.count { it == color }).isEqualTo(completeGlyphPixels)
            }
        } finally {
            foreground.recycle()
        }
        saveReviewImage("launcher-icon", bitmap)
    }

    @Test
    @Config(qualifiers = "w404dp-h874dp-mdpi")
    fun `renders welcome in light appearance`() {
        setAuth(CustomerAppearanceMode.LIGHT)

        composeRule.onNodeWithTag("customer-auth-login").assertIsDisplayed()
        captureRoot("welcome-light")
    }

    @Test
    @Config(qualifiers = "w404dp-h874dp-mdpi")
    fun `renders welcome in dark appearance`() {
        setAuth(CustomerAppearanceMode.DARK)

        composeRule.onNodeWithTag("customer-auth-login").assertIsDisplayed()
        captureRoot("welcome-dark")
    }

    @Test
    @Config(qualifiers = "w404dp-h874dp-mdpi")
    fun `renders login in light appearance`() {
        setAuth(CustomerAppearanceMode.LIGHT)
        composeRule.onNodeWithTag("customer-auth-login").performScrollTo().performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("customer-login-screen").assertIsDisplayed()
        captureRoot("login-light")
    }

    @Test
    @Config(qualifiers = "w404dp-h874dp-mdpi")
    fun `renders registration in light appearance`() {
        setAuth(CustomerAppearanceMode.LIGHT)
        composeRule.onNodeWithTag("customer-auth-register").performScrollTo().performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("customer-registration-screen").assertIsDisplayed()
        captureRoot("registration-light")
    }

    @Test
    @Config(qualifiers = "w404dp-h874dp-mdpi")
    fun `renders registration with focused first name field`() {
        setRegistration(CustomerAppearanceMode.LIGHT)

        composeRule.onNodeWithTag("customer-registration-first-name").performClick()
        dispatchKeyboardInset(338)
        composeRule.waitForIdle()
        captureRoot("registration-ime-first-name")
    }

    @Test
    @Config(qualifiers = "w404dp-h874dp-mdpi")
    fun `renders registration scrolled to contact fields`() {
        setRegistration(CustomerAppearanceMode.LIGHT)

        dispatchKeyboardInset(338)
        composeRule.onNodeWithTag("customer-registration-screen")
            .performScrollToNode(hasTestTag("customer-registration-phone"))
        composeRule.onNodeWithTag("customer-registration-phone").assertIsDisplayed()
        captureRoot("registration-ime-contact")
    }

    @Test
    @Config(qualifiers = "w404dp-h874dp-mdpi")
    fun `renders registration submit state after all fields are filled`() {
        var submitted = false
        setRegistration(
            CustomerAppearanceMode.LIGHT,
            onRegister = { _, _, _, _, _, _, _ -> submitted = true },
        )

        composeRule.onNodeWithTag("customer-registration-login").performScrollTo()
            .performTextReplacement("client")
        composeRule.onNodeWithTag("customer-registration-first-name").performScrollTo()
            .performTextReplacement("Иван")
        composeRule.onNodeWithTag("customer-registration-last-name").performScrollTo()
            .performTextReplacement("Петров")
        composeRule.onNodeWithTag("customer-registration-email").performScrollTo()
            .performTextReplacement("client@example.test")
        composeRule.onNodeWithTag("customer-registration-phone").performScrollTo()
            .performTextReplacement("+79990000000")
        composeRule.onNodeWithTag("customer-registration-password").performScrollTo()
            .performTextReplacement("secret123")
        composeRule.onNodeWithTag("customer-registration-password-repeat").performScrollTo()
            .performTextReplacement("secret123")
        composeRule.onNodeWithTag("customer-registration-submit").performScrollTo().performClick()
        composeRule.runOnIdle { assertThat(submitted).isTrue() }
        captureRoot("registration-submitted")
    }

    @Test
    @Config(qualifiers = "w404dp-h874dp-mdpi")
    fun `renders profile in light appearance`() {
        setProfile(CustomerAppearanceMode.LIGHT)
        composeRule.onNodeWithTag("profile-screen").assertIsDisplayed()
        captureRoot("profile-light")
    }

    @Test
    @Config(qualifiers = "w404dp-h874dp-mdpi")
    fun `renders profile in dark appearance`() {
        setProfile(CustomerAppearanceMode.DARK)
        composeRule.onNodeWithTag("profile-screen").assertIsDisplayed()
        captureRoot("profile-dark")
    }

    @Test
    @Config(qualifiers = "w404dp-h874dp-mdpi")
    fun `renders password recovery in light appearance`() {
        setAuth(CustomerAppearanceMode.LIGHT)
        composeRule.onNodeWithTag("customer-auth-login").performScrollTo().performClick()
        composeRule.onNodeWithTag("customer-login-recovery").performScrollTo().performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("customer-password-recovery-screen").assertIsDisplayed()
        captureRoot("password-recovery-light")
    }

    @Test
    fun `renders catalog in light appearance`() {
        setCatalog(CustomerAppearanceMode.LIGHT)

        composeRule.onNodeWithTag("cabin-review-cabin").assertIsDisplayed()
        composeRule.onNodeWithTag("cart-fab").assertIsDisplayed()
        val catalogBounds = composeRule.onNodeWithTag("catalog-screen").fetchSemanticsNode().boundsInRoot
        val cartBounds = composeRule.onNodeWithTag("cart-fab").fetchSemanticsNode().boundsInRoot
        assertThat(catalogBounds.bottom).isAtMost(cartBounds.top)
        captureRoot("catalog-light")
    }

    @Test
    fun `renders catalog in dark appearance`() {
        setCatalog(CustomerAppearanceMode.DARK)

        composeRule.onNodeWithTag("cabin-review-cabin").assertIsDisplayed()
        composeRule.onNodeWithTag("cart-fab").assertIsDisplayed()
        captureRoot("catalog-dark")
    }

    @Test
    fun `renders cart in light appearance`() {
        setCatalog(CustomerAppearanceMode.LIGHT)
        composeRule.onNodeWithTag("cart-fab").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("cart-screen").performScrollToNode(hasTestTag("cart-delivery-button"))
        composeRule.onNodeWithTag("cart-delivery-button").assertIsDisplayed()
        composeRule.onNodeWithTag("cart-additional-${reviewCabin.unitId}").assertIsDisplayed()
        composeRule.onNodeWithTag("cart-remove-${reviewCabin.unitId}").assertIsDisplayed()
        val additionalTextLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("+ Дополнительно", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(additionalTextLayouts) }
        assertThat(additionalTextLayouts).hasSize(1)
        assertThat(additionalTextLayouts.single().lineCount).isEqualTo(1)
        captureRoot("cart-light")
    }

    @Test
    fun `renders opaque delivery responsibility dialog`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerStoreLaunchGate {
                    DeliveryResponsibilityDialog(
                        selectedCabinCount = 2,
                        siteCabinCapacity = 2,
                        privateSiteAccessConfirmed = false,
                        failedTripChargeAcknowledged = false,
                        busy = false,
                        onSiteCabinCapacity = {},
                        onPrivateSiteAccess = {},
                        onFailedTripAcknowledgement = {},
                        onDismiss = {},
                        onConfirm = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("delivery-responsibility-dialog").assertIsDisplayed()
        val dialogWindow = requireNotNull(requireNotNull(ShadowDialog.getLatestDialog()).window)
        captureView("delivery-responsibility-dialog-light", dialogWindow.decorView)
    }

    private fun setAuth(appearanceMode: CustomerAppearanceMode) {
        composeRule.setContent {
            CompositionLocalProvider(LocalCustomerStoreVideoBackgroundEnabled provides false) {
                CustomerTheme(appearanceMode) {
                    CustomerStoreLaunchGate {
                        CustomerAppContent(CustomerAppState.SignedOut())
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun setRegistration(
        appearanceMode: CustomerAppearanceMode,
        onRegister: (String, String, String, String, String, String, String) -> Unit = { _, _, _, _, _, _, _ -> },
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalCustomerStoreVideoBackgroundEnabled provides false) {
                CustomerTheme(appearanceMode) {
                    CustomerAuthenticationScreen(
                        message = null,
                        submitting = false,
                        onLogin = { _, _, _ -> },
                        onRegister = onRegister,
                        initialPage = CustomerAuthenticationPage.REGISTRATION,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun dispatchKeyboardInset(bottom: Int) {
        composeRule.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(composeRule.activity.window.decorView, insets)
        }
        composeRule.waitForIdle()
    }

    private fun setProfile(appearanceMode: CustomerAppearanceMode) {
        composeRule.setContent {
            CompositionLocalProvider(LocalCustomerStoreVideoBackgroundEnabled provides false) {
                CustomerTheme(appearanceMode) {
                    CustomerStoreLaunchGate {
                        ProfileFormScreen(
                            existing = reviewState().profile!!,
                            busy = false,
                            onSave = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun setCatalog(appearanceMode: CustomerAppearanceMode) {
        composeRule.setContent {
            CustomerTheme(appearanceMode) {
                CustomerStoreLaunchGate {
                    CustomerAppContent(CustomerAppState.Ready(reviewState()))
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun captureRoot(name: String) {
        captureView(name, composeRule.activity.window.decorView)
    }

    private fun captureView(name: String, view: View) {
        lateinit var image: Bitmap
        composeRule.runOnIdle {
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        saveReviewImage(name, image)
    }

    private fun saveReviewImage(name: String, bitmap: Bitmap) {
        try {
            assertThat(bitmap.width).isGreaterThan(0)
            assertThat(bitmap.height).isGreaterThan(0)
            val colors = buildSet {
                repeat(8) { horizontal ->
                    repeat(8) { vertical ->
                        add(bitmap.getPixel(horizontal * (bitmap.width - 1) / 7, vertical * (bitmap.height - 1) / 7))
                    }
                }
            }
            assertThat(colors.size).isAtLeast(4)
            System.getenv("RWMS_CUSTOMER_VISUAL_OUTPUT")
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.also { assertThat(it.mkdirs() || it.isDirectory).isTrue() }
                ?.resolve("$name.png")
                ?.let { file -> FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        } finally {
            bitmap.recycle()
        }
    }

    private fun reviewState(): CustomerWorkflowState = CustomerWorkflowState(
        bootstrapping = false,
        profile = CustomerProfile(
            id = "review-profile",
            version = 1,
            entityType = CustomerEntityType.INDIVIDUAL,
            firstName = "Иван",
            lastName = "Петров",
            phone = "+79990000000",
        ),
        warehouses = listOf(reviewWarehouse),
        selectedWarehouse = reviewWarehouse,
        inquiryId = "review-inquiry",
        cabins = listOf(reviewCabin),
        selectedCabinIds = setOf(reviewCabin.unitId),
        cart = CustomerCart(
            inquiryId = "review-inquiry",
            warehouseId = reviewWarehouse.id,
            version = 4,
            state = "DRAFT",
            cabins = listOf(reviewCabin),
        ),
    )

    private companion object {
        val reviewWarehouse = CustomerWarehouse(
            id = "review-spb",
            name = "Склад Санкт-Петербург",
            city = "Санкт-Петербург",
            timezone = "Europe/Moscow",
            depotLatitude = 59.9343,
            depotLongitude = 30.3351,
        )
        val reviewCabin = CustomerCabin(
            unitId = "review-cabin",
            version = 1,
            accountingNo = "БК-42",
            pricingVersion = 3,
            monthlyPriceRubles = 18_000,
            type = "Офисная бытовка",
            finish = "Графит",
            dimensions = "6,0 × 2,4 × 2,6 м",
            category = "Офисная",
            linoleum = true,
            characteristics = listOf("Панорамное остекление", "Электрика и освещение"),
        )
    }
}
