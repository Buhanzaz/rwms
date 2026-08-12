# RWMS DriverApp

`driver-app/` — самостоятельный Android-клиент для водителей склада. Его
package — `dev.buhanzaz.rwms.driver`; приложение не разделяет локальное
состояние с ManagerApp или WorkerApp.

## Продуктовая поверхность

В главном меню ровно два пункта:

- **Работа на складе** показывает отфильтрованную сервером доску водителя для
  логистики, ремонта/КПП и внутренних перемещений.
- **Загрузки** показывают сохранённые действия и загрузки фото и позволяют
  повторить операцию после ошибки.

Профиль и выход доступны отдельным вторичным действием над меню, а не третьим
пунктом. Действия водителя: `TAKE`, `PAUSE`, `RESUME` и `COMPLETE`. DriverApp
никогда не предлагает `JOIN`: этот сценарий принадлежит стропальщику в
WorkerApp. Для задания `LOGISTICS_DRIVER` завершение требует хотя бы одно
готовое (`READY`) фото результата.

Навигацией и меню владеют
[`DriverApp.kt`](app/src/main/java/dev/buhanzaz/rwms/driver/DriverApp.kt) и
[`DriverDownloads.kt`](app/src/main/java/dev/buhanzaz/rwms/driver/DriverDownloads.kt).

## Аутентификация и публичные API

Публичный PKCE-клиент — `rwms-driver-android` со scopes
`openid profile offline_access driver.tasks`. HTTPS callback —
`<public gateway>/auth/driver/callback`; native login-клиент проверяет и
потребляет его в памяти, а APK не экспортирует OAuth deep-link receiver.

Вызовы task-board используют только `/api/task-board/driver/v1/**`. Детали
логистики загружаются через `/api/logistics/v1/driver-tasks/{taskId}`, media —
через `/api/media/v1/**`. Весь трафик идёт через настроенный публичный HTTPS
gateway. См.
[`DriverAuthConfiguration.kt`](core-auth/src/main/java/dev/buhanzaz/rwms/driver/core/auth/DriverAuthConfiguration.kt)
и
[`DriverGatewayApi.kt`](core-network/src/main/java/dev/buhanzaz/rwms/driver/core/network/DriverGatewayApi.kt).

## Офлайн-работа, камера и загрузки

Room хранит авторизованную проекцию и транзакционный локальный outbox.
WorkManager повторяет допустимые временные ошибки со стабильными operation ID.
Снятые JPEG остаются зашифрованными в приватном хранилище приложения, пока не
завершены reservation, upload и finalize; перезапуск процесса или устройства
их не теряет. CameraX нормализует EXIF-ориентацию и применяет существующие
ограничения размера/разрешения. После reconnect или конфликта серверное
состояние остаётся авторитетным.

Реализация находится в `core-database/`, `core-sync/`, `core-media/` и
`feature-camera/`. Экран «Загрузки» — recovery-поверхность; он не раскрывает
зашифрованные payload или приватные пути файлов.

## Realtime и Firebase

SSE и опциональные FCM data messages служат только сигналами инвалидации.
DriverApp регистрирует Firebase Installation ID (`targetKind=FID`), а не
устаревший registration token, затем обновляет авторитетное REST-состояние.
Событие `TASK_JOIN_AVAILABLE`, предназначенное для стропальщиков, приложение
намеренно отклоняет.

Firebase включается только когда
`driver-app/app/google-services.json` предоставлен вне source control. Файл
игнорируется Git и должен описывать Firebase Android-приложение
`dev.buhanzaz.rwms.driver`; репозиторий не содержит учётных данных.

## Сборка и сфокусированные проверки

Нужны JDK 17 и Android SDK 36:

```bash
cd driver-app
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew \
  :core-auth:testDebugUnitTest \
  :core-network:testDebugUnitTest \
  :feature-task-detail:testDebugUnitTest \
  :core-sync:testDebugUnitTest \
  :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug
```

Без `google-services.json` APK собирается и использует SSE/polling, но
end-to-end проверка доставки FCM недоступна. Для end-to-end проверки gateway
также нужны заведённая учётная запись водителя и соответствующий OAuth-клиент.
