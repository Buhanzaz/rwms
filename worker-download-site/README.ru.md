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

Текущий manifest намеренно имеет статус 'pending': проверенный worker APK ещё
не опубликован. У pending-manifest поля 'downloadUrl', 'sha256' и
'publishedAt' должны быть 'null', поэтому страница не может отобразить битую
ссылку на скачивание.

Для manifest со статусом 'published' обязательны:

- неизменяемый HTTPS download URL без учётных данных;
- SHA-256 из 64 строчных шестнадцатеричных символов;
- дата публикации в ISO-формате; и
- фактические package/version metadata проверенного APK.

Сайт не хранит APK. Проверенный артефакт публикуется отдельно по
неизменяемому URL из manifest.

## Сборка и проверка

Достаточно Node.js 20 или новее; у сборки нет package-зависимостей.

    npm run check
    npm run build

Сборка валидирует manifest, рендерит static HTML в '.site/' и создаёт там
Cloudflare Worker entry point. 'npm run check' дополнительно доказывает, что
pending-состояние не содержит URL скачивания, а сгенерированный Worker имеет
ожидаемые security и root-route handling.

## Безопасность публикации

Публикация — внешнее release-действие и требует явного разрешения. Не
развёртывайте pending-manifest, mutable debug-файл или артефакт, у которого не
проверены package identity, версия, signing certificate и SHA-256. После
авторизованного deploy проверьте public root response и байты точного APK:
успешная deploy-команда или HTTP 200 не доказывают корректность релиза.
