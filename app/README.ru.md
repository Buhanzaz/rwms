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
- На `401` gateway выполняется одна process-wide сериализованная попытка refresh,
  общая для foreground UI и WorkManager. После захвата этой блокировки каждый
  repository перечитывает зашифрованное состояние, поэтому параллельный запрос
  повторно использует уже ротированные peer-ом access и refresh token. Временный
  transport-сбой refresh сохраняет зашифрованную сессию и показывается как
  ошибка подключения. Сессия становится непригодной только при отсутствующем/
  отозванном refresh token или терминальном ответе `invalid_grant`/
  `access_denied`.
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
фиксирует все 64 объявленных метода `RwmsApi` на их канонический публичный
OpenAPI-источник: 62 фиксированных gateway routes и ровно два разрешённых
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
При открытии редактора перемещения эта дата инициализируется по каноническому
IANA timezone выбранного склада-источника в инъецированный момент серверных
часов. Submit проверяет ту же warehouse-local дату и никогда не использует
`LocalDate.now()` Android-устройства; отсутствующий склад или некорректный
timezone дают явную ошибку.

Перед открытием приёмки отдельной бытовки Manager читает arrival preflight с
версиями документа и строки. Отсутствующие очереди ремонта на складе назначения
или ошибка проверки блокируют приёмку. Для продолжения ремонта требуется явно
выбрать приоритет от 1 до 5; без ремонта команда передаёт `priority: null`.
Приоритет, исходные версии, медиа и ключ команды сохраняются вместе при
восстановлении фоновой загрузки. Закрытие приёмки или смена перемещения отменяет
применение поздних ответов preflight/media; после изменения версии приёмку нужно
открыть заново.

Удалённые медиа используют единый индекс файлов в памяти и LRU-лимит 256 файлов /
256 МиБ. Загрузки, открытые редакторы и отображаемые фото/видео удерживают свои
файлы; пока они используются, размер кэша может превышать лимит. Замена или
закрытие редактора, освобождение просмотрщика и ошибка либо отмена пакетной
загрузки освобождают удержание. Фото инвентаризации копируются в долговечные
черновики до освобождения временных файлов; очистка кэша не удаляет каталоги
черновиков или снимков камеры.

## Экраны и владение локальным состоянием

Navigation graph включает manager home/menu, фоновые загрузки, логистические
возвраты/отгрузки/перемещения, инвентаризацию, сметы и ремонты обслуживания,
приёмку и camera/media шаги. Экран не владеет долговечным бизнес-переходом: он
посылает публичную команду и затем наблюдает или обновляет серверный результат.

Экран активной инвентаризации по мере ввода фильтрует существующие карточки
бытовок по префиксу канонического номера и не выводит отдельный список
автодополнения. Действие добавления появляется только тогда, когда ни один
загруженный номер бытовки склада не начинается с введённого текста. Проверка
идёт через паспорт, фотографии, мебель, каталог работ, поздние детали проверки
и подтверждение. Характеристики, линолеум, счётчики санблока и комментарий
проверки находятся на шаге поздних деталей, а не в паспорте. Сохранённый осмотр
открывается по тем же шагам в режиме только для чтения. На каждом шаге есть
действие «Изменить осмотр»: дополнение включает редактирование сохранённых
данных на текущем шаге, а перезапись возвращает к паспорту с чистой ревизией
осмотра. Durable queue отправляет любой повторный режим только пока находка
сохраняет точную свежепрочитанную ревизию: общий session fence можно обновить
после изменения другой бытовки, но fence более нового осмотра этой же бытовки
никогда не перебазируется. Непустой план осмотра, сметы или первичного ремонта также может хранить
явный выбор «направить на капитальный ремонт»; inventory editor располагает его
после «создать перемещение на ремонт», и выбор одного направления снимает
другое. Произвольные этапы работ/материалов сохраняют выбранный маршрут доски
ремонта; чтение frozen plan использует snapshot routing строки, чтобы назначить
каждую строку ровно одному этапу даже при повторяющихся ID работ каталога.
Maintenance-service остаётся владельцем итоговой рассчитанной классификации
CAPITAL и существующего логистического/приёмочного цикла. См.
[`InventoryScreens.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/screens/InventoryScreens.kt)
и
[`ManagerNavGraph.kt`](src/main/java/dev/buhanzaz/rwms/manager/navigation/ManagerNavGraph.kt).

В меню ремонтного цикла пункт «Кап. ремонты» расположен сразу после
«Ремонты». Он читает принадлежащий maintenance-service endpoint активных
капремонтов; строки с рассчитанной сложностью CAPITAL исключаются из обычной
таблицы ремонтов. Карточка капремонта раскрывает authoritative упорядоченные
этапы, называет их очереди и показывает работы и материалы отдельными
столбцами с точным количеством и единицей. Раскрытие только отображает данные
и не создаёт и не продвигает логистическое задание. См.
[`MaintenanceScreen.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/screens/MaintenanceScreen.kt).

Обычная очередь ремонтов читает ровно один публичный агрегатный snapshot
task-board без query-измерений даты и shadow. Она отображает один адаптивный
вертикальный поток, сгруппированный по серверному порядку очередей, и никогда
не показывает и не использует `scheduledDate` записи: выбор дат,
горизонтальные колонки дат, drag-and-drop, обычное перемещение и команды обмена
датами удалены. Task-board владеет каноническим порядком и возвращает полный
незавершённый маршрут. ManagerApp связывает с этим
компактным списком только текущую maintenance-стадию `REAL`; необязательное
выделение полного пути и отображение плана на день принадлежат панели. Детали
карточки только открывают принадлежащий maintenance ремонт.
`ManagerReadCache` хранит один account-and-warehouse-scoped агрегатный snapshot
с его ETag для fail-soft восстановления. См.
[`RepairQueueView.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/screens/RepairQueueView.kt),
[`ManagerMaintenanceReadCoordinator.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/ManagerMaintenanceReadCoordinator.kt)
и
[`RwmsApi.kt`](src/main/java/dev/buhanzaz/rwms/manager/network/RwmsApi.kt).

Обновление maintenance проходит все объявленные сервером страницы смет,
обычных ремонтов и активных капремонтов. Нулевая страница сохраняет свой ETag и
семантику cache fallback, а следующие страницы читаются последовательно.
Приёмка запрашивает все отфильтрованные сервером страницы `PENDING` и загружает
детали ремонтов в устойчивом порядке проекции, держа одновременно не более
четырёх запросов.

При открытии приёмки приложение разрешает полный owner-authorized snapshot
preview до показа редактора ремонта и сохраняет раздельными три области
отображения. Заголовок бытовки соответствует общему набору источников WorkerApp:
из агрегатных reference исключаются все элементы `mediaReferences` строк работ,
а для чтения сначала по порядку пробуются доказательства этапов
`TASK_BOARD_ENTRY`/`WORK_RESULT`, затем исходное доказательство сметы, inventory
finding или ремонта. Это работает и для завершённого ремонта из инвентаризации,
в историческом ответе которого уже нет `inventorySource`. Карточка работы
встраивает только запланированные `mediaReferences` этой строки; если их нет,
карточка сразу переходит к действиям «Принято» и «Переделать». В конце этапа
встраиваются только фотографии результата рабочего, спроецированные в
`evidence` этого этапа. Потоковые тела копируются на IO dispatcher в полностью
записанный приватный cache-файл приложения; проверенный copy fallback обслуживает
Android-устройства, которые отказываются выполнить финальный rename внутри одной
директории. Кэшированный preview или original используется повторно только после
того, как его публичный content endpoint заново авторизовал точные owner, media
identity, generation, склад и context. Ограниченные полосовые блокировки promotion
сохраняют уже полностью записанный immutable cache-файл при ошибке параллельного
копирования. Если хотя бы одно указанное фото всё ещё нельзя разрешить, редактор
не публикуется с частичным слайдером. Пустая область не выводит кнопку фото,
предупреждение или заглушку. У каждого слайдера есть стрелки и счётчик, а нажатие
по кадру открывает тот же набор с точного индекса в полноэкранном просмотре.
Отдельная съёмка фотографии приёмки остаётся обязательной, потому что
каноническая команда приёмки требует хотя бы одну media reference приёмки. См.
[`MaintenanceAcceptanceScreen.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/screens/MaintenanceAcceptanceScreen.kt),
[`AcceptanceMediaPolicies.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/AcceptanceMediaPolicies.kt),
[`ManagerMaintenanceReadCoordinator.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/ManagerMaintenanceReadCoordinator.kt)
и
[`maintenance-service.yaml`](../contracts/openapi/maintenance-service.yaml).

При первом добавлении работы из maintenance catalog новые, выбранные из галереи
и повторно использованные фото состояния сразу отображаются удаляемыми
миниатюрами, как и при редактировании. В блоке фото работы есть три прямых
действия: полноразмерная кнопка «Добавить фото» открывает встроенный
CameraX-экран, а «Из сделанных» и «Из галереи» расположены строкой ниже.
«Из сделанных» включает локальные и уже загруженные фотографии с предыдущего
шага состояния; выбранная фотография переносится в работу без повторной
загрузки. «Из галереи» открывает системный Android document picker без
предварительного вопроса об источнике. В
камере любая клавиша громкости один раз срабатывает как затвор и поглощается
активной Activity либо окном диалога, включая камеру задачи в полноэкранном
диалоге. Обычный режим `ФОТО` использует CameraX-политику минимальной задержки,
автоматическое разрешение около 12 MP и по умолчанию отключённый HDR; ночной,
явно выбранное полное разрешение и явно включённый HDR сохраняют обработку с
приоритетом качества. При обновлении прежняя автоматическая настройка HDR
однократно отключается, после чего менеджер может снова включить HDR. См.
[`MaintenanceScreen.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/screens/MaintenanceScreen.kt),
[`MainActivity.kt`](src/main/java/dev/buhanzaz/rwms/manager/MainActivity.kt) и
[`ManagerCameraScreen.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/components/ManagerCameraScreen.kt).

Тот же CameraX-экран записывает MP4-видео с необязательным звуком микрофона.
Picker-ы доказательств инвентаризации, сметы, ремонта и работы принимают JPEG,
PNG, WebP, MP4 и WebM; локальные и скачанные по owner-authorized пути видео
проигрываются с явными Media3 controls и никогда не назначаются фото-обложкой.
Для READY-видео приложение запрашивает сжатый вариант `PLAYBACK`, оставляя
авторизованный сервером `ORIGINAL` только как compatibility fallback старых
video generation. См.
[`ManagerPhotos.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/components/ManagerPhotos.kt)
и
[`ManagerMediaCoordinator.kt`](src/main/java/dev/buhanzaz/rwms/manager/ui/coordinator/ManagerMediaCoordinator.kt).

В UI статичное изображение остаётся одним app-owned оригиналом. Durable worker
разрешает его EXIF-ориентацию до кодирования ровно трёх WebP-частей: `SMALL`,
`MEDIUM` и `LARGE`; детерминированное снижение качества и разрешения удерживает
их общий размер не больше 1 MiB, а одновременно декодируется не больше одного
оригинала высокого разрешения. Оригинал не загружается. Create request
объявляет только эти варианты, каждая часть использует собственный стабильный
idempotency key и same-origin PUT
`/api/media/v1/upload-sessions/{id}/variants/{kind}/content`, а finalize
отправляет квитанции всех трёх объектов. MP4/WebM и явные compatibility
source-upload сохраняют прежнюю форму `/content`. См.
[`ImageUploadBundleEncoder.kt`](src/main/java/dev/buhanzaz/rwms/manager/media/ImageUploadBundleEncoder.kt),
[`MediaUploadPayloads.kt`](src/main/java/dev/buhanzaz/rwms/manager/media/MediaUploadPayloads.kt)
и [`MediaUploader.kt`](src/main/java/dev/buhanzaz/rwms/manager/media/MediaUploader.kt).

Локальное хранение намеренно ограничено:

- зашифрованным OAuth-state и предпочтением выбранного склада;
- account-scoped, server-verified read snapshot в `ManagerReadCache` для
  восстановления maintenance, repair queue и inventory после смерти процесса;
- одним AES-GCM-authenticated snapshot inventory editor на проверенную пару
  account/warehouse, включая текущий navigation step и app-private копии
  выбранных image/video originals; он удаляется только после явного закрытия
  либо долговечного enqueue, поэтому после смерти процесса возобновляется тот
  же осмотр без назначения черновика другому account или warehouse;
- account-and-workspace-warehouse-scoped документом background-upload,
  app-private оригиналами media, restart-safe сгенерированными WebP-частями и
  identity WorkManager, чтобы уже созданная загрузка могла возобновиться только
  для своего неизменяемого владельца; после долговечной записи READY-reference
  outbox удаляет свой scoped оригинал и сгенерированные части, но никогда не
  удаляет user-owned URI галереи;
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
| `ManagerInventoryCoordinator` | Inventory-чтения, crash-safe editor state, scoped cache и upload outbox |
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
преобразуются общим backend-клиентом в пользовательские ошибки. Отказ записи
очереди при повторе операции или фотографии передаётся тому же обработчику:
исходные команда и фотографии сохраняются, новая фоновая задача не запускается. Терминальная
ошибка аутентификации очищает непригодную сессию, а временная недоступность
refresh этого не делает. `409 Conflict` требует от экрана обновить/rebase
серверную версию до повтора. Потоковые response body preview и original
копируются в приватный кэш приложения на IO-dispatcher до передачи локального
URI в Compose; UI-dispatcher не читает потоковое сетевое тело. Перед отправкой
первой долговечной проверки инвентаризации worker перечитывает активную бытовку,
затем fencing-ревизию
сессии и сохраняет новую ревизию finding, если во время загрузки медиа её
сдвинуло live-изменение статуса/снимка asset. Если после этой сверки сервис
инвентаризации всё же возвращает `409 INVENTORY_VERSION_CONFLICT` с detail
`Inventory revision is stale`, worker выполняет ещё один цикл
чтения/rebase/сохранения. Оба прохода разрешены только для `NOT_INSPECTED` в
`IDLE` или `SOURCE_CREATED`; ушедшая бытовка, незавершённое создание исходной
бытовки или уже сохранённый/повторный осмотр остаются fail-closed и не могут
быть перезаписаны повтором из очереди. До обновления поколений сохранённых медиа и проверки
ревизии worker сверяет возможный потерянный ответ с полным сохранённым осмотром: наблюдениями,
поколениями фотографий и обложкой, всеми выбранными параметрами плана, порядком строк и
идентичностями замороженных этапов. Точное совпадение долговечно завершает только шаг осмотра;
оставшееся идемпотентное задание по мебели всё равно выполняется. Конфликтный или недоказуемый
результат остаётся в очереди с понятным сообщением без перезаписи серверных данных. Такая же
сверка выполняется после ошибки сохранения или предварительной проверки ревизии.

Manager обрабатывает одновременно до трёх отправок бытовок. Повторы одной
операции в той же области account-and-warehouse выполняются последовательно,
как и итоговые чтения и команды, использующие общую ревизию сессии инвентаризации.
Все worker разделяют один слот кодирования изображений и не больше четырёх
логических byte-heavy media upload, сохраняя исходный порядок каждого пакета.
Каждый пакет ставит на этот общий лимит ограниченное число фотографий, поэтому
другие бытовки могут отправляться до завершения большого пакета.
Image bundle дополнительно разделяют
общий предел шести параллельных PUT частей: три части одной фотографии могут
передаваться одновременно без неограниченного fan-out между фотографиями.
После принятого create/upload/finalize ожидание READY больше не занимает
логический upload permit, поэтому следующие файлы используют uplink, пока
проекция предыдущего media становится видимой.
После исчерпания повторов проверки владельца фотографии показаны конкретная
подсказка для повтора и подтверждение сохранности фотографий в очереди;
внутренний текст исключений пользователю не передаётся.

Workspace получает авторитетный `/me`, допустимую роль, актуальные warehouse
grants и выбранный warehouse до показа или возобновления долговечной работы.
Listing, retry и cancellation очереди, originals, input/tags WorkManager и
unique work names несут одну и ту же область account-and-warehouse. Перед
первым side effect worker сверяет `/me` с этим неизменяемым владельцем и каждым
warehouse, сохранённым в command/media. При logout и смене warehouse приложение
отменяет и дожидается завершения всех работающих и ожидающих upload work,
включая закрытие auth snapshot каждого worker, прежде чем OAuth-сессию можно
очистить или заменить.

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

Любая release-задача создания артефакта закрывается с ошибкой, если
`-PsigningPropertiesFile` не указывает на читаемый внешний файл с
`storeFile`, `storePassword`, `keyAlias` и `keyPassword` либо указанный keystore
недоступен. Используйте
[`signing.properties.example`](signing.properties.example) только как шаблон
названий ключей; настоящие signing material остаются вне Git:

```bash
bash ./gradlew \
  -PsigningPropertiesFile=/secure/path/rwms-manager-signing.properties \
  assembleRelease
```

Соберите точный reviewed source scope, затем зафиксируйте package name APK,
`versionCode`, `versionName`, signing certificate и SHA-256 до публикации.
`manager-download-site/release-trust-policy.json` дополнительно фиксирует
сертификаты, допустимые для каналов `PRODUCTION` и `INTERNAL_TEST`; пустой
production allowlist намеренно запрещает production-публикацию. Установите
именно этот APK на device/emulator, пройдите вход через нужный
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
