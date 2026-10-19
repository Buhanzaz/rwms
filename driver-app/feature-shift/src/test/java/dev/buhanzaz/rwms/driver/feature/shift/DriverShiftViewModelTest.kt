package dev.buhanzaz.rwms.driver.feature.shift

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.yandex.mapkit.map.MapWindow
import dev.buhanzaz.rwms.driver.core.database.DriverDatabase
import dev.buhanzaz.rwms.driver.core.database.DriverLocalStore
import dev.buhanzaz.rwms.driver.core.database.DriverShiftSnapshotEntity
import dev.buhanzaz.rwms.driver.core.database.PendingPayloadCodec
import dev.buhanzaz.rwms.driver.core.database.PendingShiftCommand
import dev.buhanzaz.rwms.driver.core.network.AuthenticatedGatewayMonitor
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayApi
import dev.buhanzaz.rwms.driver.core.network.DriverGatewayClient
import dev.buhanzaz.rwms.driver.core.network.DriverShiftDto
import dev.buhanzaz.rwms.driver.core.network.TodayDriverShiftDto
import dev.buhanzaz.rwms.driver.core.sync.DriverProjectionWriter
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Verifies the real ViewModel's fenced durable command and duplicate admission against Room. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DriverShiftViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var database: DriverDatabase
    private var viewModel: DriverShiftViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DriverDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        dispatcher.scheduler.runCurrent()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `briefing command keeps server fence and duplicate click cannot enqueue another effect`() = runTest(dispatcher) {
        val store = DriverLocalStore(database, object : PendingPayloadCodec {
            override fun encrypt(plainText: String) = plainText
            override fun decrypt(encoded: String) = encoded
        }, json)
        val today = TodayDriverShiftDto(
            enabled = true, serverTime = "2026-09-06T10:00:00Z", nextRequiredAction = "SHOW_DAILY_BRIEFING",
            shift = DriverShiftDto("shift", 7, "driver", "Driver", "warehouse", "2026-09-06", "Europe/Moscow", "PLANNED"),
        )
        database.shiftSnapshotDao().upsert(DriverShiftSnapshotEntity(
            "driver", "shift", "2026-09-06", true, today.nextRequiredAction,
            json.encodeToString(today), today.serverTime, authoritativeShiftVersion = 7, updatedAtEpochMillis = 1,
        ))
        val api = Proxy.newProxyInstance(DriverGatewayApi::class.java.classLoader, arrayOf(DriverGatewayApi::class.java)) {
                _, method, _ -> error("Cached shift must not call gateway: ${method.name}")
        } as DriverGatewayApi
        val requests = mutableListOf<String>()
        val model = DriverShiftViewModel(store, DriverGatewayClient(api, json), DriverProjectionWriter(database, json),
            requests::add, AuthenticatedGatewayMonitor(), NoTraffic, json)
        viewModel = model
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        model.bind("driver")
        model.confirmBriefing()
        model.confirmBriefing()

        val state = withContext(Dispatchers.IO) {
            withTimeout(5_000) { model.state.first { it.pendingCount == 1 && it.error != null } }
        }
        val row = store.pendingOutbox("driver").single()
        val command = json.decodeFromString<PendingShiftCommand>(store.decryptOutboxPayload(row))
        assertThat(row.userId).isEqualTo("driver")
        assertThat(row.entryId).isEqualTo("shift")
        assertThat(command.shiftId).isEqualTo("shift")
        assertThat(command.expectedVersion).isEqualTo(7)
        assertThat(command.action).isEqualTo("BRIEFING_SEEN")
        assertThat(command.operationId).isNotEmpty()
        assertThat(requests).containsExactly("driver")
        assertThat(state.error).isEqualTo("Не удалось сохранить действие. Обновите смену и повторите.")
        assertThat(state.today?.nextRequiredAction).isEqualTo("SHOW_DAILY_BRIEFING")
    }

    /** The command test never attaches a map or requests external traffic. */
    private object NoTraffic : TrafficBriefingProvider {
        override fun attach(mapWindow: MapWindow) = Unit
        override fun detach(mapWindow: MapWindow) = Unit
        override suspend fun load(latitude: Double, longitude: Double) = TrafficBriefing(null, false)
    }
}
