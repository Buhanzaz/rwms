# Сайт загрузки RWMS Клиент

`client-download-site/` — отдельная русскоязычная страница загрузки
CustomerApp из `client-app/` (`dev.buhanzaz.rwms.client`). Она не размещает APK
ManagerApp или WorkerApp и не проксирует API RWMS.

English version: [README.md](README.md).

## Источник релиза

`release.json` — отображаемый источник релиза. У pending-релиза поля
`downloadUrl`, `sha256` и `publishedAt` равны null, поэтому страница не может
предложить битый или непроверенный APK. Published-релиз обязан ссылаться на
точный неизменяемый путь артефакта и подтверждённые package/version/signing/
checksum-факты.

## Проверка

Используйте Node.js 20 или новее:

    RWMS_CUSTOMER_APK=/absolute/path/to/rwms-customer.apk npm run check

Для published-manifest проверка считает hash точно этого APK и сверяет его с
`release.json`; manifest также фиксирует сертификат подписи, базовую ревизию и diff приложения.
Статическая страница адаптирована для телефона и компьютера и не требует установки runtime-
пакетов. Публикуйте страницу и проверенный APK только после авторизованного release-
процесса, проверив сам APK.

`release-trust-policy.json` является channel-specific allowlist сертификатов.
`npm run check` требует non-debug package/version канала `PRODUCTION`, полное
происхождение source, один signer в manifest и точное совпадение APK по SHA-256,
package, version и signer из `aapt`/`apksigner`. Сертификат вне policy закрывает
проверку с ошибкой; его ротация является отдельным reviewed-изменением policy.

## Runtime-путь

Планируемая VPS-поверхность — `/client-download/`; неизменяемый путь APK берётся
из `release.json` (`artifactPath`). Настройка Nginx и публикация находятся вне
этого компонента и обязаны сохранять его изоляцию от ManagerApp и WorkerApp.
