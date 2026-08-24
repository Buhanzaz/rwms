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
sha256sum /path/to/rwms-driver-0.1.18-debug.apk
node downloads-site/scripts/build-site.mjs
node downloads-site/scripts/verify-site.mjs
```

Скрипт сборки валидирует эту запись вместе с существующими записями ManagerApp
и WorkerApp и записывает только статическую общую страницу в каталог, заданный
`RWMS_DOWNLOADS_SITE_OUTPUT`.
