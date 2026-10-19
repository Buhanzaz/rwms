package dev.buhanzaz.rwms.worker

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/** Starts WorkerApp instrumentation tests with Hilt's isolated test application. */
class HiltWorkerTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(
        classLoader,
        HiltTestApplication::class.java.name,
        context,
    )
}
