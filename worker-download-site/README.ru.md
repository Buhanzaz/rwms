# Сайт загрузки RWMS Worker

'worker-download-site/' — самостоятельная static-страница загрузки
Android-приложения RWMS Worker. Это не исходники WorkerApp; сайт не
аутентифицирует пользователей, не проксирует API RWMS и не владеет доменным
состоянием.

English version: [README.md](README.md).

## Источник фактов о релизе

[release.json](release.json) — единственный вход с данными релиза, который
отображает страница. Идентичность приложения, версия и Android-требование
должны совпадать с [сборкой WorkerApp](../worker-app/app/build.gradle.kts).
`artifactPath` задаёт versioned путь APK на этом же сайте; у опубликованного
`downloadUrl` должен быть ровно этот путь без query и fragment.

У pending-manifest поля 'downloadUrl', 'sha256' и 'publishedAt' должны быть
'null', поэтому страница не может отобразить битую ссылку на скачивание.
Published-manifest собирается только с проверенным APK, который release-процесс
явно передаёт в сборку.

Для manifest со статусом 'published' обязательны:

- канал `PRODUCTION`, точные non-debug application ID и version;
- неизменяемый HTTPS download URL без учётных данных;
- SHA-256 из 64 строчных шестнадцатеричных символов;
- дата публикации в ISO-формате; и
- фактические package/version/единственный signer metadata проверенного APK,
  закреплённые в `release-trust-policy.json`; и
- переменная `RWMS_WORKER_APK`, указывающая на этот проверенный APK во время
  сборки сайта.

Исходный checkout не хранит APK. Для опубликованного релиза сборка проверяет
SHA-256 APK и кладёт его только в игнорируемый generated output. OpenNext/Sites
build размещает APK по отдельному backing-пути в
`.open-next/assets/_release-assets/`. У public `artifactPath` намеренно нет
совпадающего static asset, поэтому Worker передаёт эти backing-байты через
контролируемый download route и задаёт APK MIME type, attachment filename,
immutable cache policy и `nosniff`. Нельзя перезаписывать versioned backing
artifact следующим релизом.

## Сборка и проверка

Используйте Node.js 20 или новее и перед сборкой установите зафиксированные
зависимости:

    npm ci

    npm run check
    npm run build:cloudflare

Для manifest со статусом `published` явно передайте проверенный APK:

    RWMS_WORKER_APK=/absolute/path/to/rwms-worker.apk npm run check

`npm run check` валидирует manifest, рендерит static HTML в '.site/' и
проверяет security, root-route и download-route handling generated Worker.
`npm run build:cloudflare` затем создаёт deployable OpenNext Worker и static
assets в '.open-next/'. Для published-релиза проверки также доказывают, что
байты generated APK, package/version и signer из `aapt`/`apksigner` совпадают
с manifest и закреплённой policy канала. Неразрешённый или cross-channel
сертификат закрывает проверку с ошибкой.

## Опубликованный маршрут VPS

Активный VPS показывает этот компонент по адресу
`https://77-90-158-90.sslip.io/worker-download/`. Его неизменяемый артефакт
отдаётся по точному versioned `artifactPath` из [release.json](release.json);
`/downloads/rwms-worker.apk` — только удобный redirect, а не доказательство
релиза. Worker-файлы изолированы в `/var/www/rwms-worker-download`; страница
ManagerApp по адресу `/download/` является отдельной поверхностью и не должна
заменяться при публикации WorkerApp.

## Безопасность публикации

Публикация — внешнее release-действие и требует явного разрешения. Не
развёртывайте pending-manifest, mutable debug-файл или артефакт, у которого не
проверены package identity, версия, signing certificate и SHA-256. После
авторизованного deploy проверьте public root response и байты точного APK:
успешная deploy-команда или HTTP 200 не доказывают корректность релиза.
