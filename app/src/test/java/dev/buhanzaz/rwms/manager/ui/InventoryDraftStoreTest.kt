package dev.buhanzaz.rwms.manager.ui

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.InventorySessionDto
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Verifies process-style inventory recovery without depending on Android Keystore in Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class InventoryDraftStoreTest {
    private val context
        get() = RuntimeEnvironment.getApplication().applicationContext
    private val firstScope = InventoryDraftScope("manager-a", "warehouse-a")
    private val secondScope = InventoryDraftScope("manager-b", "warehouse-a")

    @Before
    fun setUp() {
        clearStorage()
    }

    @After
    fun tearDown() {
        clearStorage()
    }

    @Test
    fun `editor step and media survive a process-style store recreation`() = runBlocking {
        val photo = sourceFile("inspection.jpg", byteArrayOf(1, 2, 3, 4))
        val video = sourceFile(
            "walkaround.mp4",
            byteArrayOf(0, 0, 0, 16, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()),
        )
        val snapshot = snapshot(
            editor = InventoryEditorState(
                findingId = "finding-a",
                number = "CAB-17",
                outcome = "MATCHED",
                comment = "Проверка у северных ворот",
                photoUris = listOf(Uri.fromFile(photo).toString(), Uri.fromFile(video).toString()),
                coverPhotoUri = Uri.fromFile(photo).toString(),
                equipmentObservationRequested = true,
                equipmentQuantities = mapOf("chair" to "4"),
                planPriority = 73,
                planForceCapitalRepair = true,
            ),
            route = "manager-inventory-confirmation",
        )

        val durable = InventoryDraftStore(context, TestInventoryDraftCipher()).write(
            firstScope,
            snapshot,
        )
        photo.delete()
        video.delete()
        val restored = InventoryDraftStore(context, TestInventoryDraftCipher()).read(firstScope)

        assertThat(restored?.route).isEqualTo("manager-inventory-confirmation")
        assertThat(restored?.inventorySession).isEqualTo(snapshot.inventorySession)
        assertThat(restored?.editor?.comment).isEqualTo("Проверка у северных ворот")
        assertThat(restored?.editor?.equipmentQuantities).containsExactly("chair", "4")
        assertThat(restored?.editor?.planPriority).isEqualTo(73)
        assertThat(restored?.editor?.planForceCapitalRepair).isTrue()
        assertThat(restored?.editor?.coverPhotoUri).isEqualTo(durable.editor.coverPhotoUri)
        assertThat(restored?.editor?.photoUris).hasSize(2)
        val restoredFiles = restored?.editor?.photoUris.orEmpty()
            .map(Uri::parse)
            .mapNotNull { uri -> uri.path }
            .map(::File)
        assertThat(restoredFiles).hasSize(2)
        restoredFiles.forEach { file ->
            assertThat(file.isFile).isTrue()
            assertThat(file.length()).isGreaterThan(0L)
            assertThat(file.toPath().startsWith(context.filesDir.toPath())).isTrue()
        }
        assertThat(
            context.getSharedPreferences("rwms_manager_inventory_drafts", 0)
                .all.values.joinToString(),
        ).doesNotContain("Проверка у северных ворот")
    }

    @Test
    fun `account and warehouse partition cannot read another manager draft`() = runBlocking {
        val store = InventoryDraftStore(context, TestInventoryDraftCipher())
        store.write(firstScope, snapshot())

        assertThat(store.read(firstScope)?.editor?.findingId).isEqualTo("finding-a")
        assertThat(store.read(secondScope)).isNull()
    }

    @Test
    fun `explicit close clears metadata and durable media for exact scope`() = runBlocking {
        val source = sourceFile("inspection.jpg", byteArrayOf(9, 8, 7))
        val store = InventoryDraftStore(context, TestInventoryDraftCipher())
        val durable = store.write(
            firstScope,
            snapshot(
                editor = InventoryEditorState(
                    findingId = "finding-a",
                    number = "CAB-17",
                    outcome = "MATCHED",
                    photoUris = listOf(Uri.fromFile(source).toString()),
                    coverPhotoUri = Uri.fromFile(source).toString(),
                ),
            ),
        )
        val durableFile = File(requireNotNull(Uri.parse(durable.editor.photoUris.single()).path))

        store.clear(firstScope)

        assertThat(store.read(firstScope)).isNull()
        assertThat(durableFile.exists()).isFalse()
    }

    @Test
    fun `unsupported recovery route resumes at editor`() {
        assertThat(inventoryDraftRouteOrDefault("manager-home"))
            .isEqualTo("manager-inventory-editor")
    }

    private fun snapshot(
        editor: InventoryEditorState = InventoryEditorState(
            findingId = "finding-a",
            number = "CAB-17",
            outcome = "MATCHED",
        ),
        route: String = "manager-inventory-editor",
    ) = InventoryDraftSnapshot(
        editor = editor,
        inventorySession = InventorySessionDto(
            id = "inventory-a",
            sessionRevision = 7,
            warehouseId = "warehouse-a",
            warehouseVersion = 3,
            warehouseTimeZone = "Europe/Moscow",
            businessDate = "2026-08-17",
            lifecycle = "COUNTING",
            expectedCount = 10,
            findingCount = 2,
            inspectedCount = 1,
            startedAt = "2026-08-17T08:00:00Z",
            publicationState = "NOT_PUBLISHED",
        ),
        route = route,
    )

    private fun sourceFile(name: String, bytes: ByteArray): File =
        File(context.cacheDir, name).apply { writeBytes(bytes) }

    private fun clearStorage() {
        context.getSharedPreferences("rwms_manager_inventory_drafts", 0).edit().clear().commit()
        context.filesDir.resolve("manager-inventory-drafts").deleteRecursively()
        context.cacheDir.resolve("inspection.jpg").delete()
        context.cacheDir.resolve("walkaround.mp4").delete()
    }
}

/** Deterministic authenticated-data stand-in used only by the local persistence tests. */
private class TestInventoryDraftCipher : InventoryDraftCipher {
    override fun encrypt(plainText: String, associatedData: String): String = Base64.getEncoder()
        .encodeToString("$associatedData\u0000$plainText".toByteArray(StandardCharsets.UTF_8))

    override fun decrypt(encoded: String, associatedData: String): String {
        val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        val prefix = "$associatedData\u0000"
        require(decoded.startsWith(prefix)) { "Draft associated data does not match" }
        return decoded.removePrefix(prefix)
    }
}
