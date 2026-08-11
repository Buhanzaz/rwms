# RWMS Manager для Android

`app/` — Android-приложение для менеджеров и администраторов RWMS. Это клиент
публичного gateway, а не offline-владелец состояния склада, инвентаризации,
обслуживания или логистики.

English version: [README.md](README.md).

## Публичная граница, роли и вход

- Приложение настраивается одним абсолютным HTTPS origin публичного gateway
  через `RWMS_PUBLIC_BASE_URL`. Оно вызывает только `/auth/**` и `/api/**`;
  внутренние origin сервисов, порты `localhost` и `/api/internal/**` запрещены.
- Допускаются интерактивные пользователи `SYSTEM_ADMIN`, `WMS_ADMIN` и
  `WAREHOUSE_MANAGER`, если сервер выдал им mobile access. Сервер остаётся
  источником авторизации для каждого склада и каждой команды.
- OAuth-клиент `rwms-manager-android` использует Authorization Code с PKCE S256
  и ротацией refresh token. Материал сессии шифруется неэкспортируемым ключом
  Android Keystore; пароль приложением не сохраняется.
- Redirect — это HTTPS endpoint публичного gateway
  `/auth/manager/callback`, который обрабатывается внутри
  `NativeManagerLoginClient`. Ни production-, ни Robolectric test manifest не
  публикует custom-scheme `RedirectUriReceiverActivity` из AppAuth; placeholder
  custom redirect scheme не настраивается. См.
  [`ManagerAuth.kt`](src/main/java/dev/buhanzaz/rwms/manager/auth/ManagerAuth.kt),
  [main manifest](src/main/AndroidManifest.xml) и
  [test manifest](src/test/AndroidManifest.xml).

### Проверка transport-контракта

[`RwmsApiContractBoundaryTest.kt`](src/test/java/dev/buhanzaz/rwms/manager/network/RwmsApiContractBoundaryTest.kt)
фиксирует все 62 объявленных метода `RwmsApi` на их канонический публичный
OpenAPI-источник: 60 фиксированных gateway routes и ровно два разрешённых
динамических media routes. Тест запрещает internal/private namespaces и service
origins, заранее разрешает каждый request/response converter Retrofit/Moshi и
проверяет репрезентативные decode/encode fixtures для всех восьми потребляемых
владельцев контрактов: auth, warehouse, inventory, asset, logistics,
maintenance, task board и media. Бинарные media bodies проверяются converter-ом
и намеренно не представлены JSON-fixtures. Тесты динамических upload и
variant-read также доказывают, что их callers отклоняют чужой origin до вызова
Retrofit.

Проекции логистических документов передают только календарную дату
(`scheduledDate`); менеджерский клиент не использует устаревшее поле времени
планирования.

## Экраны и владение локальным состоянием

Navigation graph включает manager home/menu, фоновые загрузки, логистические
возвраты/отгрузки/перемещения, инвентаризацию, сметы и ремонты обслуживания,
приёмку и camera/media шаги. Экран не владеет долговечным бизнес-переходом: он
посылает публичную команду и затем наблюдает или обновляет серверный результат.

Локальное хранение намеренно ограничено:

- зашифрованным OAuth-state и предпочтением выбранного склада;
- account-scoped, server-verified read snapshot в `ManagerReadCache` для
  восстановления maintenance, repair queue и inventory после смерти процесса;
- account-and-workspace-warehouse-scoped документом background-upload,
  app-private оригиналами media и identity WorkManager, чтобы уже созданная
  загрузка могла возобновиться только для своего неизменяемого владельца;
- AES-GCM-зашифрованным snapshot maintenance catalog, разделённым по
  проверенным account и warehouse и защищённым неэкспортируемым ключом Android
  Keystore.

Кэш maintenance catalog — только локальная оптимизация чтения. Он не является
доменным источником истины, а все экраны всё равно должны соблюдать серверные
ревизии и авторизацию. Ownerless queue версии 1 и plaintext-записи catalog
перемещаются в локальный quarantine и игнорируются; приложение не угадывает их
владельца, не восстанавливает их в workspace и не отправляет сохранённые в них
command/media data.
Реализующие эти client-boundary исходники —
[`BackgroundUploadStore.kt`](src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadStore.kt),
[`BackgroundUploadCoordinator.kt`](src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadCoordinator.kt),
[`BackgroundUploadWorker.kt`](src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadWorker.kt)
и
[`MaintenanceCatalogCache.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/MaintenanceCatalogCache.kt).

## Внутренняя структура оркестрации

`ManagerViewModel` — стабильный UI-фасад над одним авторитетным
`ManagerUiState`; он перенаправляет действия и не содержит междоменных
workflow. Внутренние координаторы разделены по независимо изменяющимся
ответственностям:

| Компонент | Ответственность |
| --- | --- |
| `ManagerCommandRuntime` | Только общий state reduction, coroutine scope, mapping Problem Details и механика invalidation сессии |
| `ManagerWorkspaceCoordinator` | Аутентификация/workspace, связность и background lifecycle |
| `ManagerInventoryCoordinator` | Inventory-чтения, editor state, scoped cache и upload outbox |
| `ManagerShipmentCoordinator` | Список/detail отгрузки, планирование, готовность furniture-task и подтверждение |
| `ManagerReturnCoordinator` | Доказательства осмотра возврата, приёмка без повреждений и запуск сметы |
| `ManagerTransferCoordinator` | Создание перемещения, убытие, доказательства прибытия, reconciliation и отмена |
| `ManagerMaintenanceCatalogCoordinator` | Кэш каталога в памяти/на диске, revision и refresh jobs |
| `ManagerMaintenanceReadCoordinator` | Maintenance-чтения, repair board и чтения приёмки |
| `ManagerMaintenanceEditorCoordinator` | Единый form-state machine сметы/ремонта/переделки |
| `ManagerMaintenancePersistenceCoordinator` | Persistence черновика, отправка, замена и background upload work |
| `ManagerMediaCoordinator` | Owner-scoped чтения медиа |

Чистые inventory- и maintenance editor policies содержат детерминированные
reducers, но не сетевые или persistence-эффекты. Cross-workflow вызовы идут
через узкие порты вроде catalog access, maintenance refresh и editor close;
координаторы не вызывают фасад или late-bound registry. Они могут хранить
только jobs, mutexes, cache metadata и request-generation fences; серверное
состояние остаётся авторитетным. Основные исходники —
[`ManagerViewModel.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/ManagerViewModel.kt)
и пакет [`coordinator/`](src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/).

## Загрузки, ошибки и конкурентность

Media и эффекты команд используют публичный media/API-flow и стабильные
operation identifier. Upload queue записывает прогресс и ошибку для явного
повтора; она не выдумывает успешную команду offline. Problem Details
преобразуются общим backend-клиентом в пользовательские ошибки. Ошибка
аутентификации очищает непригодную сессию, а `409 Conflict` требует от экрана
обновить/rebase серверную версию до повтора.

Workspace получает авторитетный `/me`, допустимую роль, актуальные warehouse
grants и выбранный warehouse до показа или возобновления долговечной работы.
Listing, retry и cancellation очереди, originals, input/tags WorkManager и
unique work names несут одну и ту же область account-and-warehouse. Перед
первым side effect worker сверяет `/me` с этим неизменяемым владельцем и каждым
warehouse, сохранённым в command/media. При logout и смене warehouse приложение
отменяет и дожидается завершения upload work, включая закрытие auth snapshot
worker, прежде чем OAuth-сессию можно очистить или заменить.

Для изменяющей команды используйте заданный контрактом `expectedVersion`, ETag
или иной fencing token, а для повторяемого create/effect — заданный контрактом
idempotency key или стабильный внешний ID. Сейчас у приложения нет бизнесовой
SSE-проекции: чтения и долговечная upload-работа перепроверяются через gateway.

## Сборка и focused checks

Нужны JDK 17 и установленный Android SDK. Из `app/`:

```bash
bash ./gradlew compileDebugKotlin
bash ./gradlew testDebugUnitTest --tests 'dev.buhanzaz.rwms.manager.network.RwmsApiContractBoundaryTest'
bash ./gradlew testDebugUnitTest
bash ./gradlew -PRWMS_PUBLIC_BASE_URL=https://rwms.example.test assembleDebug
```

`RWMS_PUBLIC_BASE_URL` должен быть HTTPS origin без path, query, fragment и
user info. Значение по умолчанию — тестовый публичный gateway; оно не даёт
оснований заявлять об end-to-end production-интеграции.

## Целостность релиза

Соберите точный reviewed source scope, затем зафиксируйте package name APK,
`versionCode`, `versionName`, signing certificate и SHA-256 до публикации.
Установите именно этот APK на device/emulator, пройдите вход через нужный
публичный gateway и проверьте первый profile/workspace request и изменённый
manager-flow. Debug APK, успешная Gradle-задача или HTTP 200 сами по себе не
являются доказательством релиза.

## Источник истины

Канонические публичные операции находятся в `contracts/openapi/`; владеющий
сервис определяет бизнес-инвариант каждой операции. Generated/network DTO,
локальные cache и WorkManager-record — это boundary/client state, но не общие
доменные модели и не persistence backend.

Межсервисная бизнес-последовательность, которую отображает приложение,
проиндексирована в документе
[полного цикла бытовки](../docs/project-knowledge/cabin-lifecycle.ru.md).
