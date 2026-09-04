package dev.buhanzaz.rwms.rentalmanager

import android.app.Application
import dev.buhanzaz.rwms.rentalmanager.network.RentalManagerBackend

/** Application-scoped transport keeps the encrypted OAuth session stable across configuration changes. */
class RentalManagerApplication : Application() {
    val backend: RentalManagerBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RentalManagerBackend(this, BuildConfig.PUBLIC_BASE_URL)
    }
}
