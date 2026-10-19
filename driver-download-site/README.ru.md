# Запись релиза RWMS Driver

Эта директория хранит запись релиза DriverApp
(`dev.buhanzaz.rwms.driver.debug`). Она намеренно не зеркалирует артефакты
ManagerApp или WorkerApp.

Публичная посадочная страница — отдельная общая витрина
`https://77-90-158-90.sslip.io/downloads/`. Неизменяемый APK DriverApp Nginx
отдаёт из собственного web-root
`/var/www/rwms-driver-download/`; общая страница только ссылается на него.

`release.json` переводится из `pending` в `published` только после сборки
точного проверенного APK, проверки его package/version и SHA-256 и готовности
неизменяемого маршрута Nginx. У ожидающего релиза нет публичной ссылки и
контрольной суммы, поэтому он не должен давать ссылку на скачивание.

## Проверка

```bash
node --test scripts/release-trust.test.mjs
RWMS_DRIVER_APK=/path/to/rwms-driver-0.1.20-debug.apk \
  node scripts/verify-release.mjs
```

`release-trust-policy.json` разделяет allowlist сертификатов `INTERNAL_TEST` и
`PRODUCTION`. Проверка через `aapt` и `apksigner` сверяет hash точного APK,
package/version, DN единственного signer и его SHA-256. Текущая запись явно
относится к `INTERNAL_TEST`; пустой production allowlist не позволяет выдать
этот debug-артефакт за production-релиз. Ротация сертификата требует отдельного
reviewed-изменения policy.

Отдельный builder общей download-page потребляет эту проверенную запись, но
никогда не владеет и не копирует APK DriverApp.
