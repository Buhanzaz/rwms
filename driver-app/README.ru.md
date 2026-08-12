# RWMS DriverApp

`driver-app/` — самостоятельный Android-клиент для водителей склада. Его
package — `dev.buhanzaz.rwms.driver`; приложение не разделяет локальное
состояние с ManagerApp или WorkerApp.

## Продуктовая поверхность

В главном меню ровно три пункта:

- **Работа на складе** показывает отфильтрованную сервером доску водителя для
  ремонта/КПП, внутренних перемещений и общих перемещений
  `WAREHOUSE_DRIVERS`. Личная логистика здесь не дублируется.
- **Логистика** показывает только назначенные этому водителю
  (`ASSIGNED_DRIVER`) отгрузки и возвраты на выбранную дату. При открытии
  выбрана текущая дата по часовому поясу телефона; неподвижный русский заголовок
  месяца следует за центральной датой горизонтальной карусели соседних дат.
  Карточка открывает существующие полные детали ходки, media и действий.
- **Загрузки** показывают сохранённые действия и загрузки фото и позволяют
  повторить операцию после ошибки.

Профиль и выход доступны отдельным вторичным действием над меню, а не четвёртым
пунктом. Действия водителя: `TAKE`, `PAUSE`, `RESUME` и `COMPLETE`. DriverApp
никогда не предлагает `JOIN`: этот сценарий принадлежит стропальщику в
WorkerApp. Для задания `LOGISTICS_DRIVER` завершение требует хотя бы одно
готовое (`READY`) фото результата.
Камера включается только когда задание фактически находится в
`IN_PROGRESS`, а текущий водитель является его активным участником (либо
локальный долговечный `TAKE` ожидает синхронизации). Экран деталей отдельно
показывает готовые на сервере и сохранённые локально, но ещё не готовые фото,
поэтому сам факт съёмки не выдаётся за разрешение на завершение.

Навигацией и меню владеют
[`DriverApp.kt`](app/src/main/java/dev/buhanzaz/rwms/driver/DriverApp.kt) и
[`DriverDownloads.kt`](app/src/main/java/dev/buhanzaz/rwms/driver/DriverDownloads.kt).
Проекцией по датам владеет
[`LogisticsScreen.kt`](feature-tasks/src/main/java/dev/buhanzaz/rwms/driver/feature/tasks/LogisticsScreen.kt).

## Аутентификация и публичные API

Публичный PKCE-клиент — `rwms-driver-android` со scopes
`openid profile offline_access driver.tasks`. HTTPS callback —
`<public gateway>/auth/driver/callback`; native login-клиент проверяет и
потребляет его в памяти, а APK не экспортирует OAuth deep-link receiver.

Вызовы task-board используют только `/api/task-board/driver/v1/**`. Полные
детали логистики загружаются через
`/api/logistics/v1/driver-tasks/{taskId}` только для точного назначения
`ASSIGNED_DRIVER`; общая работа `WAREHOUSE_DRIVERS` остаётся в доступных пулу
водителей деталях task-board. Media использует `/api/media/v1/**`. Весь трафик
идёт через настроенный публичный HTTPS gateway. См.
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

Если старая версия приложения сняла фото до `TAKE` и сервер поэтому отклонил
его резервирование, последующий `TAKE` или `RESUME` атомарно ставит тот же
зашифрованный JPEG со стабильными operation ID в очередь повторно, пока
исходная съёмка покрывается активным 24-часовым офлайн-допуском. Просроченное
фото не получает вымышленную новую дату и не отправляется: водитель должен
снять новое.

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
