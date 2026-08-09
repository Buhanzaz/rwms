# Тестовый модуль baseline profile

[English version](README.md)

Это намеренно отдельный модуль `com.android.test`, нацеленный на `:app`, чтобы
device-backed сценарий baseline profile имел собственную область и не входил в
application module.

Стабильный Gradle plugin `androidx.baselineprofile` 1.4.1 сейчас отклоняет AGP
9 application со встроенным Kotlin (`Module :app is not a supported android
module`). Поэтому plugin здесь не применяется: переход на доступную alpha 1.5
нарушил бы правило stable-only builds. Когда выйдет стабильный совместимый с
AGP 9 plugin, примените его здесь и в `:app`, затем добавьте login/board startup
journey и сгенерируйте профиль на физическом устройстве API 33+ или на
подготовленном managed device.
