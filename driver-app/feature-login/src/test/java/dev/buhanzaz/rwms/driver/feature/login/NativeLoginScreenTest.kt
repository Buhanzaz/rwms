package dev.buhanzaz.rwms.driver.feature.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.ui.RwmsDriverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeLoginScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows driver credentials immediately and submits them`() {
        var submitted: Pair<String, String>? = null
        compose.setContent {
            RwmsDriverTheme {
                LoginScreen(
                    failure = null,
                    isSubmitting = false,
                    onLogin = { username, password -> submitted = username to password },
                )
            }
        }

        compose.onNodeWithText("Вход для водителя").assertIsDisplayed()
        compose.onNodeWithTag("driver-login").performTextInput(" driver.login ")
        compose.onNodeWithTag("driver-password").performTextInput("secret password")
        compose.onNodeWithContentDescription("Показать пароль").performClick()
        compose.onNodeWithContentDescription("Скрыть пароль").assertIsDisplayed()
        compose.onNodeWithText("Войти").performClick()

        compose.runOnIdle {
            assertThat(submitted).isEqualTo("driver.login" to "secret password")
        }
    }

    @Test
    fun `shows progress and a driver-specific error`() {
        compose.setContent {
            RwmsDriverTheme {
                LoginScreen(
                    failure = "Неверный логин или пароль либо вход для водителя отключён",
                    isSubmitting = true,
                    onLogin = { _, _ -> error("Disabled login must not submit") },
                )
            }
        }

        compose.onNodeWithText("Неверный логин или пароль либо вход для водителя отключён")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Выполняется вход").assertIsDisplayed()
        compose.onNodeWithTag("driver-login").assertIsNotEnabled()
        compose.onNodeWithTag("driver-password").assertIsNotEnabled()
    }

    @Test
    fun `password is not retained when login screen is recreated`() {
        var generation by mutableIntStateOf(0)
        compose.setContent {
            RwmsDriverTheme {
                key(generation) {
                    LoginScreen(
                        failure = null,
                        isSubmitting = false,
                        onLogin = { _, _ -> },
                    )
                }
            }
        }
        compose.onNodeWithTag("driver-login").performTextInput("driver.login")
        compose.onNodeWithTag("driver-password").performTextInput("must-not-persist")

        compose.runOnIdle { generation++ }

        val loginText = compose.onNodeWithTag("driver-login")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        val passwordText = compose.onNodeWithTag("driver-password")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertThat(loginText).isEmpty()
        assertThat(passwordText).isEmpty()
        compose.onNodeWithText("Войти").assertIsNotEnabled()
    }
}
