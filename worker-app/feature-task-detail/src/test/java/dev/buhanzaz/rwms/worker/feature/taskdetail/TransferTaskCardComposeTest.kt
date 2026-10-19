package dev.buhanzaz.rwms.worker.feature.taskdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the transfer projection remains readable without inventing missing warehouse data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp")
class TransferTaskCardComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cardShowsExactCargoAndExplicitlyReportsMissingRoute() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 780.dp)) {
                    TransferTaskCard(
                        TransferTaskPresentation(
                            route = null,
                            cargoLines = listOf("БТ-172", "БТ-311"),
                            materialLines = listOf("Кровать — 8 шт"),
                            comments = listOf("Логист: Проверить крепление"),
                            routeSteps = emptyList(),
                            instructions = listOf("Сверьте груз", "Подтвердите выгрузку"),
                        ),
                    )
                }
            }
        }

        compose.onNodeWithTag("transfer-task-card").assertIsDisplayed()
        compose.onNodeWithText("Межскладское перемещение").assertIsDisplayed()
        compose.onNodeWithText("Точки отправления и назначения не переданы в карточку задания")
            .assertIsDisplayed()
        compose.onNodeWithText("БТ-172").assertIsDisplayed()
        compose.onNodeWithText("Кровать — 8 шт").assertIsDisplayed()
        compose.onNodeWithText("Логист: Проверить крепление").assertIsDisplayed()
    }
}
