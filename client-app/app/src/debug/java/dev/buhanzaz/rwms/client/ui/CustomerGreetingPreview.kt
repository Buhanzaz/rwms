package dev.buhanzaz.rwms.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider

/** Fixed animation phases let Compose Preview render the real welcome without a clock or player. */
class CustomerGreetingPhases : PreviewParameterProvider<Float> {
    override val values = sequenceOf(0f, 0.2f, 0.4f, 0.7f, 0.85f, 1f)
}

@Preview(name = "Welcome · waves", widthDp = 404, heightDp = 874, showBackground = true)
@Composable
internal fun CustomerGreetingPreview(@PreviewParameter(CustomerGreetingPhases::class) progress: Float) {
    CustomerAuthenticationScreen(
        message = null,
        submitting = false,
        onLogin = { _, _, _ -> },
        onRegister = { _, _, _, _, _, _, _ -> },
        greetingPreviewProgress = progress,
    )
}

@Preview(name = "Fields · login", widthDp = 404, heightDp = 874, showBackground = true)
@Composable
internal fun CustomerLoginFieldsPreview() {
    CustomerAuthenticationScreen(
        message = null,
        submitting = false,
        onLogin = { _, _, _ -> },
        onRegister = { _, _, _, _, _, _, _ -> },
        initialPage = CustomerAuthenticationPage.LOGIN,
    )
}

@Preview(name = "Fields · registration", widthDp = 404, heightDp = 874, showBackground = true)
@Composable
internal fun CustomerRegistrationFieldsPreview() {
    CustomerAuthenticationScreen(
        message = null,
        submitting = false,
        onLogin = { _, _, _ -> },
        onRegister = { _, _, _, _, _, _, _ -> },
        initialPage = CustomerAuthenticationPage.REGISTRATION,
    )
}

@Preview(name = "Fields · recovery", widthDp = 404, heightDp = 874, showBackground = true)
@Composable
internal fun CustomerRecoveryFieldsPreview() {
    CustomerAuthenticationScreen(
        message = null,
        submitting = false,
        onLogin = { _, _, _ -> },
        onRegister = { _, _, _, _, _, _, _ -> },
        initialPage = CustomerAuthenticationPage.PASSWORD_RECOVERY,
    )
}
