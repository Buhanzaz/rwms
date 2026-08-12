# Baseline-profile test module

[Русская версия](README.ru.md)

This is intentionally a `com.android.test` module targeting `:app`, so a
device-backed baseline profile journey has a dedicated home without joining the
application module.

The stable `androidx.baselineprofile` Gradle plugin 1.4.1 currently rejects an
AGP 9 built-in-Kotlin application (`Module :app is not a supported android
module`). It is therefore not applied here: moving to the available 1.5 alpha
would violate the stable-only build policy. Once a stable AGP 9-compatible
plugin is released, apply it here and to `:app`, then add the login/board
startup journey and generate on a physical API 33+ device or a provisioned
managed device.
