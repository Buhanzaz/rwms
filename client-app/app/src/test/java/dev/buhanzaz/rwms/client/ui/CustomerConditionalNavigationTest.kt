package dev.buhanzaz.rwms.client.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerWarehouse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose-level proof that signed-out and signed-in customer destinations cannot coexist. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerConditionalNavigationTest {
    /** Compose semantics environment for the conditional navigation assertions. */
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `signed out shows login and hides profile workflow`() {
        composeRule.setContent {
            CustomerTheme { CustomerAppContent(CustomerAppState.SignedOut()) }
        }

        composeRule.onNodeWithText("Вход").assertExists()
        composeRule.onNodeWithTag("profile-screen").assertDoesNotExist()
    }

    @Test
    fun `signed in without profile shows profile and hides login`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(
                    CustomerAppState.Ready(CustomerWorkflowState(bootstrapping = false)),
                )
            }
        }

        composeRule.onNodeWithTag("profile-screen").assertExists()
        composeRule.onNodeWithText("Вход").assertDoesNotExist()
    }

    @Test
    fun `created warehouse session opens catalog even while its first page is empty`() {
        composeRule.setContent {
            CustomerTheme {
                CustomerAppContent(
                    CustomerAppState.Ready(
                        CustomerWorkflowState(
                            bootstrapping = false,
                            profile = CustomerProfile(
                                entityType = CustomerEntityType.INDIVIDUAL,
                                firstName = "Иван",
                                lastName = "Петров",
                                phone = "+79990000000",
                            ),
                            selectedWarehouse = CustomerWarehouse(
                                id = "c89b65f1-2891-4176-bd88-1d231e869a25",
                                name = "СПБ",
                                city = "Санкт-Петербург",
                                timezone = "Europe/Moscow",
                                depotLatitude = 59.763806,
                                depotLongitude = 30.471798,
                            ),
                            inquiryId = "00000000-0000-0000-0000-000000000601",
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("warehouse-screen").assertDoesNotExist()
        composeRule.onNodeWithTag("catalog-screen").assertExists()
    }
}
