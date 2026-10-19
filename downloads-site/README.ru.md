# RWMS Downloads

`downloads-site/` — публичная общая посадочная страница пяти Android-клиентов.
Она не владеет APK и не копирует их:

- `manager-download-site/release.json` владеет записью релиза ManagerApp;
- `rental-manager-download-site/release.json` владеет записью релиза Rental Manager;
- `driver-download-site/release.json` владеет записью релиза DriverApp;
- `worker-download-site/release.json` владеет записью релиза WorkerApp;
- `client-download-site/release.json` владеет записью релиза CustomerApp.

Страница доступна по адресу
`https://77-90-158-90.sslip.io/downloads/`. Каждая карточка ссылается только
на неизменяемый URL версии APK из записи владельца. Nginx отдаёт эти APK из
разных каталогов, поэтому артефакт Manager, Rental Manager, Driver, Worker или Customer никогда
не копируется в каталог релиза другого приложения.

## Сборка и проверка

```bash
RWMS_DOWNLOADS_SITE_OUTPUT=/tmp/rwms-downloads-site npm run build
RWMS_DOWNLOADS_SITE_OUTPUT=/tmp/rwms-downloads-site npm run verify
```

Сборка проверяет, что опубликованы ровно пять ожидаемых package identity. Она
закрыто завершается с ошибкой, если запись ожидает публикации, некорректна, не
содержит SHA-256 или указывает на изменяемый/неверсионный URL. `verify`
проверяет созданный HTML и неизменяемые ссылки; он не выдумывает метаданные
APK.

## Публикация на VPS

1. Соберите и проверьте APK каждого приложения независимо.
2. Обновите только его собственную запись релиза до `published`, указав точные
   SHA-256 и неизменяемый URL.
3. Сгенерируйте эту страницу во временный каталог и проверьте её.
4. Атомарно замените только `/var/www/rwms-app-downloads/` созданной
   статической страницей; APK Driver и Customer положите только в их отдельные
   каталоги `/var/www/rwms-driver-download/` и `/var/www/rwms-client-download/`.
5. Проверьте и перезагрузите Nginx, затем запросите страницу и каждый
   неизменяемый URL APK, сопоставив SHA-256 ответа.

Все текущие APK явно помечаются как тестовые, если запись владельца указывает
на debug-package. Производственная подпись и политика хранения остаются
отдельными решениями release engineering.

Rental Manager использует существующий канал внутренних debug-сборок и отдельный
каталог APK `/var/www/rwms-rental-manager-download/`.
