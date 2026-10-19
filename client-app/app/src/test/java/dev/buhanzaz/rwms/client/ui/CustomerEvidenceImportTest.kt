package dev.buhanzaz.rwms.client.ui

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.client.data.CustomerEvidenceFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Runs the actual picker, copying, cancellation and draft deletion through the problem form. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomerEvidenceImportTest {
    @get:Rule
    val compose = createComposeRule()

    private val application: Application = RuntimeEnvironment.getApplication()
    private val visible = mutableStateOf(true)
    private val busy = mutableStateOf(false)
    private val provider = EvidenceProvider()
    private val openedOnMain = AtomicBoolean()
    private val submitted = mutableListOf<List<CustomerEvidenceFile>>()
    private val blockedStreams = mutableListOf<BlockedStream>()

    @Before
    fun setUp() {
        directory().deleteRecursively()
        provider.attachInfo(application, ProviderInfo().apply { authority = AUTHORITY })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @After
    fun tearDown() {
        blockedStreams.forEach { it.release.countDown() }
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(10_000) { blockedStreams.all { !it.started.get() || it.closed.get() } }
        directory().deleteRecursively()
    }

    @Test
    fun `slow copying keeps the form responsive and cancellation retains completed drafts`() {
        val first = source("first") { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        val held = blockedSource()
        render(listOf(first, held.first))
        compose.onNode(hasSetTextAction()).performTextInput("Не хватает оборудования")
        compose.onNodeWithText("Галерея").performScrollTo().assertIsDisplayed().performClick()
        assertThat(visible.value).isTrue()
        compose.waitUntil(10_000) { held.second.reading.get() }

        compose.onNodeWithText("Импорт 1 из 2").assertExists()
        compose.onNodeWithText("Отправить").assertIsNotEnabled()
        compose.onNodeWithText("Галерея").assertIsNotEnabled()
        compose.onNodeWithText("Камера").assertIsNotEnabled()
        assertThat(openedOnMain.get()).isFalse()
        assertThat(provider.readOnMain.get()).isFalse()
        compose.onNodeWithText("Отменить импорт").performScrollTo().assertIsDisplayed().performClick()
        held.second.release.countDown()
        awaitIdleImport()
        assertThat(files()).hasSize(1)
        compose.onNodeWithText("Отправить").performScrollTo().assertIsEnabled().performClick()
        assertThat(submitted.single().single().file.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `closing the form cancels unfinished copying and removes both partial and completed files`() {
        val first = source("first") { ByteArrayInputStream(byteArrayOf(1)) }
        val held = blockedSource()
        render(listOf(first, held.first))
        compose.onNodeWithText("Галерея").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { held.second.reading.get() }
        compose.onNodeWithText("Импорт 1 из 2").assertExists()

        compose.runOnIdle { visible.value = false }
        held.second.release.countDown()
        compose.waitUntil(10_000) { held.second.closed.get() && files().isEmpty() }
        assertThat(submitted).isEmpty()
        assertThat(held.second.timedOut.get()).isFalse()
    }

    @Test
    fun `removal respects upload busy state and retains a failed deletion for explicit retry`() {
        render(listOf(source("first") { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }))
        compose.onNodeWithText("Галерея").performScrollTo().assertIsDisplayed().performClick()
        awaitIdleImport()
        val file = files().single()
        val remove = compose.onNodeWithContentDescription("Удалить ${file.name}")
        compose.runOnIdle { busy.value = true }
        remove.assertIsNotEnabled()
        assertThat(file.exists()).isTrue()

        // Only this generated test draft is replaced with an undeletable nonempty directory.
        val saved = file.resolveSibling("saved-draft")
        check(file.renameTo(saved))
        check(file.mkdir())
        file.resolve("blocker").writeText("test")
        compose.runOnIdle { busy.value = false }
        remove.performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Не удалось удалить файл. Повторите попытку").assertExists()
        remove.assertExists()
        check(file.deleteRecursively())
        check(saved.renameTo(file))
        remove.performScrollTo().assertIsDisplayed().performClick()
        remove.assertDoesNotExist()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `one picker result admits at most twenty attachments`() {
        render((1..21).map { index -> source("file-$index") { ByteArrayInputStream(byteArrayOf(index.toByte())) } })
        compose.onNode(hasSetTextAction()).performTextInput("Описание проблемы")
        compose.onNodeWithText("Галерея").performScrollTo().assertIsDisplayed().performClick()
        awaitIdleImport()
        compose.onNodeWithText("Галерея").assertIsNotEnabled()
        compose.onNodeWithText("Отправить").performScrollTo().assertIsDisplayed().performClick()
        assertThat(submitted.single()).hasSize(20)
        assertThat(files()).hasSize(20)
        assertThat(submitted.single().map { it.file.readBytes().single().toInt() }).containsExactlyElementsIn(1..20)
    }

    @Test
    fun `empty unsupported and oversized selections report failure while a valid sibling survives`() {
        val unsupportedOpened = AtomicBoolean()
        val empty = source("empty") { ByteArrayInputStream(byteArrayOf()) }
        val unsupported = source("unsupported") {
            unsupportedOpened.set(true)
            ByteArrayInputStream(byteArrayOf(1))
        }
        val oversized = source("oversized") { GeneratedStream(200L * 1024L * 1024L + 1) }
        val valid = source("valid") { ByteArrayInputStream(byteArrayOf(7, 8, 9)) }
        render(listOf(empty, unsupported, oversized, valid))
        compose.onNode(hasSetTextAction()).performTextInput("Проблема")
        compose.onNodeWithText("Галерея").performScrollTo().assertIsDisplayed().performClick()
        awaitIdleImport()
        compose.onNodeWithText("Не удалось импортировать файл").assertExists()
        compose.onNodeWithText("Отправить").performScrollTo().assertIsDisplayed().performClick()
        assertThat(unsupportedOpened.get()).isFalse()
        assertThat(files()).hasSize(1)
        assertThat(submitted.single().single().file.readBytes()).isEqualTo(byteArrayOf(7, 8, 9))
    }

    private fun render(selected: List<Uri>) {
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
            ) {
                val clip = ClipData("evidence", arrayOf("video/mp4"), ClipData.Item(selected.first()))
                selected.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().apply { clipData = clip })
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                CustomerTheme {
                    if (visible.value) {
                        CustomerProblemDialog(
                            accountingNo = "БК-1", phase = "До приёмки", busy = busy.value,
                            onDismiss = { visible.value = false },
                            onSubmit = { _, _, evidence -> submitted += evidence },
                        )
                    }
                }
            }
        }
    }

    private fun source(name: String, stream: () -> InputStream): Uri = Uri.parse("content://$AUTHORITY/$name").also { uri ->
        Shadows.shadowOf(application.contentResolver).registerInputStreamSupplier(uri) {
            if (Looper.myLooper() == Looper.getMainLooper()) openedOnMain.set(true)
            stream()
        }
    }

    private fun blockedSource(): Pair<Uri, BlockedStream> {
        val stream = BlockedStream().also(blockedStreams::add)
        return source("slow") { stream } to stream
    }

    private fun awaitIdleImport() {
        compose.waitUntil(20_000) { !compose.onNodeWithText("Отменить импорт").exists() }
        compose.waitForIdle()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.exists(): Boolean =
        runCatching { fetchSemanticsNode() }.isSuccess

    private fun directory() = File(application.cacheDir, "customer-problem-evidence")
    private fun files() = directory().listFiles()?.toList().orEmpty()

    /** Supplies a MIME type while the resolver shadow supplies the actual controlled streams. */
    private class EvidenceProvider : ContentProvider() {
        val readOnMain = AtomicBoolean()
        override fun onCreate() = true
        override fun getType(uri: Uri): String {
            if (Looper.myLooper() == Looper.getMainLooper()) readOnMain.set(true)
            return if (uri.lastPathSegment == "unsupported") "text/plain" else "video/mp4"
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    /** Pauses after the first copied byte without preventing the Compose main thread from running. */
    private class BlockedStream : InputStream() {
        val started = AtomicBoolean()
        val reading = AtomicBoolean()
        val closed = AtomicBoolean()
        val timedOut = AtomicBoolean()
        val release = CountDownLatch(1)
        private var first = true
        override fun read(): Int = error("Bulk reads expected")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            started.set(true)
            if (first) {
                first = false
                buffer[offset] = 1
                return 1
            }
            reading.set(true)
            if (!release.await(20, TimeUnit.SECONDS)) {
                timedOut.set(true)
                error("Test did not release the stream")
            }
            return -1
        }
        override fun close() { closed.set(true) }
    }

    /** Exercises the unchanged 200 MiB bound without allocating a large input array. */
    private class GeneratedStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = error("Bulk reads expected")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }

    private companion object {
        const val AUTHORITY = "customer-evidence-test"
    }
}
