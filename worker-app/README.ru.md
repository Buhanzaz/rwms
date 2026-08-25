# RWMS Рабочий для Android

'worker-app/' — самостоятельное Android-приложение для рабочих RWMS. Оно
собирается отдельно от корневой Java multi-module сборки и потребляет только
публичные gateway-контракты.

English version: [README.md](README.md).

## Публичная граница, роль и вход

- Настройте один абсолютный HTTPS origin публичного gateway через
  'RWMS_PUBLIC_BASE_URL'. Клиент использует только '/auth/**' и '/api/**'; ему
  нельзя вызывать внутренний origin сервиса, service-port на localhost или
  '/api/internal/**'.
- OAuth-клиент 'rwms-worker-android' использует Authorization Code с PKCE S256
  и scopes 'openid profile offline_access worker.tasks'. Пароль никогда не
  сохраняется. Browser/deep-link activity не экспортируется.
- Worker principal и выданный сервером worker context определяют видимые
  assignments, warehouse scope, groups и permissions. Client filtering или
  offline cache не являются авторизацией.
- Обычная и круглая launcher-иконки на Android 8+ используют adaptive-ресурс
  [`ic_launcher_worker.xml`](app/src/main/res/mipmap-anydpi-v26/ic_launcher_worker.xml).
  Полный [растр WorkerApp](app/src/main/res/drawable-nodpi/ic_launcher_worker_artwork.png)
  центрируется ресурсом
  [`ic_launcher_worker_foreground.xml`](app/src/main/res/drawable/ic_launcher_worker_foreground.xml)
  внутри безопасной adaptive-зоны. Android 12+ использует тот же компактный
  foreground на системном синем splash, а не растягивает изображение на весь
  экран. Более ранние версии Android сохраняют bitmap alias из `mipmap-anydpi`.

### Проверка transport-контракта

[`WorkerGatewayApiContractBoundaryTest.kt`](core-network/src/test/java/dev/buhanzaz/rwms/worker/core/network/WorkerGatewayApiContractBoundaryTest.kt)
фиксирует все 11 объявленных методов `WorkerGatewayApi` на их канонический
публичный OpenAPI-источник: девять фиксированных gateway routes и ровно два
разрешённых динамических media routes. Тест запрещает internal/private
namespaces и service origins, заранее разрешает каждый request/response
converter Retrofit/kotlinx.serialization и проверяет каждую активную JSON-root
fixture worker/task-board и media. Бинарные request/response bodies и
`Unit`-ответ unregister проверяются converter-ом и намеренно не представлены
JSON-fixtures. Динамические media-вызовы отклоняются до Retrofit, если не
проходят точные public same-origin path guards.

Каноническая семиполевая worker action command требует присутствия
`workerGroupId` и `evidenceId`, даже когда их значения равны null. Узкий
[`WorkerActionRequestDtoSerializer.kt`](core-network/src/main/java/dev/buhanzaz/rwms/worker/core/network/WorkerActionRequestDtoSerializer.kt)
выдаёт эти явные null keys и отклоняет отсутствующие или дополнительные
свойства команды. Глобальная JSON policy приложения остаётся неизменной для
cached projections, Problem Details, media payloads и всех остальных DTO.

## Экраны и работы, которыми владеет сервер

Navigation drawer содержит Доску задач и Загрузки; монограмма в app bar
открывает Профиль. Доска показывает только выданные в worker feed категории,
группы, assignments, KPI palette и краткие сведения о задачах. В WorkerApp нет
поверхности работы водителя или чтения данных ходки. Task-board показывает здесь
совместное задание `LOGISTICS_DRIVER` только после того, как его взял основной
исполнитель, и только подходящему secondary worker. Устаревшая ожидающая
карточка дополнительно скрывается на клиенте по fail-closed принципу.

Подходящий стропальщик берёт активное совместное задание с ID текущей группы,
если она есть: видимое действие «Взять задание» отправляет wire-команду `JOIN`.
Владеющий сервис приостанавливает таймер прежнего задания этой группы и затем
возобновляет его по серверному workflow. Участие стропальщика необязательно,
поэтому водитель может завершить задание с готовым фото до чьего-либо JOIN.
WorkerApp не показывает действие паузы; после JOIN стропальщик может возобновить
приостановленное сервером задание или завершить совместное задание. Для
завершения нужна хотя бы одна серверная фотография в состоянии `READY` от
любого активного участника; сервер закрывает одно и то же задание для обоих.
Обычные групповые роли и qualification-only категории сохраняют свои панели;
очереди и карточки задач тоже сворачиваются. На узком экране панели идут
последовательно, а начиная с 720 dp используется горизонтальная доска с двумя
колонками.

Task-board публикует только те складские очереди, чей manager-чекбокс включён.
В каждой опубликованной обычной очереди активная `REAL`-работа остаётся доступной,
а после неё идут первые настроенные manager-ом ожидающие `REAL` в авторитетном
серверном порядке. Этапы маршрута `SHADOW` никогда не отправляются в WorkerApp.
Native detail и границы `TAKE`/`JOIN` повторно проверяют то же окно публикации,
поэтому устаревшая локальная карточка не обходит изменённый план. Более ранний
этап после promotion возвращается на сохранённую позицию и входит в worker-план,
если эта позиция находится внутри настроенного окна; закреплённый ожидающий REAL
остаётся впереди. Поэтому маршрут, первым требуемым этапом которого является
электрика, может сразу прийти в очередь электриков, а блокировка СЭС и promotion
остаются server-owned.

Центрированный header доски называется «Доска задач» и не повторяет подпись
групповой роли или обычный текст прогресса синхронизации. Значок обновления в
header вращается, пока активен актуальный этап синхронизации; offline-ошибки и
блокировка из-за evidence остаются явными. Headers группы и очереди показывают
стрелки раскрытия без общего количества задач. В header карточки номер бытовки,
статус и стрелка выровнены по вертикали, дата назначения не выводится. Ожидающая
карточка один раз показывает `Выделенное время` и сложность без префикса
(`Легкий ремонт`, `Средний ремонт` или `Тяжелый ремонт`). Активная карточка
показывает ту же сложность вместе с `Время работы` и KPI справа без необходимости
раскрывать её. После раскрытия выводятся `Этап X из Y`,
`Приоритет N` и одно целое количество загруженных фото без дроби обязательного
минимума и без суффикса `таймер остановлен`. Технический maintenance-title не
выводится. Это поведение принадлежит
[`TasksScreen.kt`](feature-tasks/src/main/java/dev/buhanzaz/rwms/worker/feature/tasks/TasksScreen.kt).

«Открыть задание» открывает отдельный полноэкранный экран задачи, в том числе на
планшете и раскрытом foldable. Его app bar содержит только номер бытовки.
Содержимое начинается с листаемого слайдера общих фото; если источник задания
задаёт обложку, этот титульный кадр показывается первым. Далее идут полноширинный
таймер затраченного/оставшегося времени, сразу под ним — `Этап X/Y`, приоритет и
принадлежащая maintenance сложность без префикса `Ремонт:`, а затем коллекция фото
результата. Каждая работа выводится одной карточкой с количеством напротив названия и только её
фотографиями из `sourceMediaIds` непосредственно ниже под подписью «Фото к
работе»; затем в том же читаемом виде идут материалы. Описания задачи и
комментарии руководителя не выводятся. Нажатие фотографии открывает именно
выбранный кадр в полноэкранной листаемой галерее с масштабированием, причём для
связанного с работой фото заголовок также называет работу. Перерывы, время вне
смены и паузы остаются серверными, но на экране нет кнопок паузы и прямого
фотографирования результата. Неподдерживаемое действие «Сообщить о проблеме» не
показывается до появления серверной команды; layout определён в
[`TaskDetailScreen.kt`](feature-task-detail/src/main/java/dev/buhanzaz/rwms/worker/feature/taskdetail/TaskDetailScreen.kt).
Действие входа/возобновления остаётся в учитывающем safe area статичном footer,
а не прокручивается вместе с содержимым. Кнопка занимает всю доступную ширину,
обычный TAKE подписан «Взять задание».

Полноэкранное аутентифицированное медиа сначала загружает SMALL-preview выбранной
страницы и её непосредственных соседей, а затем постепенно заменяет выбранный
preview оригиналом. При свайпе задания отменяются независимо для каждого пути.
LRU на 16 preview сохраняет просмотренные страницы для обратного свайпа, при
этом декодированными остаются не больше трёх оригиналов; параллельно выполняются
не больше трёх загрузок, preview ограничен 256 000 пикселей, а оригинал — четырьмя
миллионами. Вытесненные и очищенные при смене маршрута bitmap освобождаются после
того, как опубликованное UI-состояние перестаёт на них ссылаться. Миниатюры detail декодируются максимум в 256 000 пикселей, не больше
трёх загружаются/декодируются параллельно, а ViewModel хранит не больше 16
последних записей и освобождает вытесненные bitmap. Временная миниатюра CameraX
декодируется максимум в 256 000 пикселей, а открываемая пользователем галерея
снятых кадров имеет отдельный предел в два миллиона пикселей. Эти границы не позволяют большому
сохранённому архиву инвентаризации превратиться в bitmap-кэш размером с Android
heap. Evidence:
[`PhotoViewModel.kt`](app/src/main/java/dev/buhanzaz/rwms/worker/PhotoViewModel.kt),
[`TaskMediaThumbnailViewModel.kt`](feature-task-detail/src/main/java/dev/buhanzaz/rwms/worker/feature/taskdetail/TaskMediaThumbnailViewModel.kt) и
[`CameraScreen.kt`](feature-camera/src/main/java/dev/buhanzaz/rwms/worker/feature/camera/CameraScreen.kt).

Для ремонта detail от task-board уже объединяет все работы и материалы текущего
последовательного пакета одной очереди. WorkerApp выводит эти упорядоченные сервером массивы и
отправляет один TAKE/COMPLETE для representative entry; приложение не дробит, не повторяет и не
закрывает локально отдельные строки стадий ремонта. Тот же COMPLETE закрывает на сервере все
оставшиеся части пакета. Для других источников сохраняется выполнение по одному entry.

При завершении открываются три полноширинных действия одинакового стиля в один
столбец: CameraX, Android photo picker и отмена. Picker принимает до десяти
изображений. Для каждого кадра CameraX и выбранного изображения сначала
физически исправляется ориентация, затем создаются локальный WebP-оригинал и
WebP-части SMALL/MEDIUM/LARGE; они шифруются и надёжно ставятся в очередь в
порядке съёмки или выбора до callback завершения. Сумма трёх upload-частей не
превышает 1 МиБ, а UI показывает
только оригинал. Последний сохранённый evidence ID передаётся в команду только там,
где logistics-контракт завершения требует выбранное фото. Sync coordinator не
отправляет `COMPLETE`, пока поставленные в очередь evidence не обработаны и
обязательное evidence не получило серверное состояние `READY`. Экран «Загрузки»
остаётся recovery-поверхностью для неудачных или ожидающих upload и явного
retry. Экран задачи записывает результат работы и WebP evidence, но локально не
решает переход задачи.

Серверные KPI ranges определяют цвета; локальная green/yellow/red политика не
выдумывается. Данные работы для рабочего не раскрывают price/cost поля.

## Offline store, outbox, sync и realtime

- Room — UI source of truth для account-scoped feed projection, task detail,
  sync progress, conflict, invalidation и outbox. Action и его outbox-row
  записываются одной локальной транзакцией.
- Чувствительные тела неотправленных command/conflict шифруются отдельно;
  локальный оригинал и три upload-части шифруются в app-private files. Они
  удаляются только после авторитетного состояния evidence `READY` от task-board.
  Эти записи — client recovery state, но не backend persistence.
- 24-часовой offline lease привязан к server time и 'elapsedRealtime'. Он
  строго ограничивает новое офлайн-действие TAKE/JOIN/PAUSE/RESUME и новую
  съёмку. У уже назначенного задания исходные фото результата и COMPLETE
  сохраняют stable IDs и фактические timestamps и после этого окна; task-board
  повторно проверяет current assignment, state, version, evidence gate и время
  не из будущего. Прошедший `deadlineAt` не блокирует это завершение; после
  принятия task-board канонический факт завершения продвигает связанную стадию
  ремонта в приёмку. Unique connected WorkManager work отправляет actions, делает
  reservation/upload/finalize evidence, ждёт 'READY', затем обновляет feed.
  Историческая ошибка фото `Действие создано вне срока offline lease` распознаётся
  как в сохранённом review-state, так и в upload-error. После следующего
  авторизованного context та же outbox-запись reservation переводится в повтор с
  новым lease, сохраняя зашифрованные bytes, identity evidence и время съёмки; новое
  фиктивное фото не создаётся. Если task-board доказывает, что задание уже `DONE`
  или `CANCELLED`, WorkerApp сохраняет зашифрованные bytes как локальные recovery-
  данные `SUPERSEDED` и удаляет только невыполнимый active replay. Downloads скрывает
  эту архивную запись, а ручной повтор сразу сообщает о постановке синхронизации в
  очередь и затем показывает сохранённый прогресс синхронизации.
- FCM и SSE несут только invalidation/revision signals. Они запускают focused
  refresh, но не заменяют авторитетный feed. Регистрация FCM-устройства
  использует `targetKind=FID` и Firebase Installation ID, а не messaging
  registration token. В foreground feed также периодически обновляется polling-ом.

## Ошибки, конкурентность и повторы

Gateway Problem Details преобразуются в явные безопасные failures. Отсутствующий
token, недействительная сессия, недоступный gateway или неподдерживаемая операция
не выдаются за mock success. Истёкший lease остаётся conflict для нового
офлайн-перехода, но не для отложенных фото результата и COMPLETE текущего
исполнителя. Conflict сохраняется, чтобы UI задачи показал server state и
потребовал осознанный refresh/retry.

После единственной возможности authenticator обновить token `401`
останавливает sync для повторного входа; `403` останавливает его для обновления
grant или действия рабочего. При `409` сохраняется conflict (и переданный
server snapshot, если он есть), после чего обновляется авторитетный feed.
Автоматический повтор failure ограничен `429`, `502`, `503`, `504` и доказанными
transport faults; некорректный Problem Details сохраняет фактический HTTP
статус с безопасным fallback.

Одна unique WorkManager job выполняет не более четырёх attempts: только
разрешённые transient failures возвращают `Result.retry()`, а WorkManager
использует сохранённый jittered seed для exponential backoff. Подтверждённое
сервером ожидающее evidence завершает этот run и ждёт позднего явного trigger.
Cancellation выходит наружу и не назначает следующий attempt. Поздний явный
foreground, FCM или user trigger может поставить новую job с теми же durable
operation identity.

Каждый mutable action использует version fence контракта и стабильный operation
ID/idempotency key, если он задан. Evidence использует упорядоченный flow
reservation → upload/finalize → 'READY'. Reservation сохраняют порядок outbox;
параллельно передаются не больше двух evidence-фото и трёх WebP-частей со
стабильными ключами каждой части. Upload paths — только точные same-origin
маршруты media API, но не MinIO credentials или внутренние storage URL. Повторы
используют долговечную operation identity; клиент не должен дублировать effect
из-за потерянного network response.

## Камера и evidence

Worker evidence создаётся на клиенте в WebP. Вход камеры/галереи ограничен 8 MP
и 15 MB, затем физическая ориентация применяется к пикселям до WebP-кодирования.
Один зашифрованный оригинал остаётся видимым локально, а три зашифрованных
варианта SMALL/MEDIUM/LARGE образуют upload payload; task-board резервирует их
детерминированный manifest и общую длину, а сумма не превышает 1 МиБ. Ultra HDR
не используется. Зашифрованный JPEG, снятый до обновления Room 8→9, сохраняет
исходную reservation и может один раз завершиться через совместимый source-upload;
media-service закрепляет тот же объект без поворота, декодирования и сжатия. Новые
evidence никогда не попадают в этот recovery-путь.
Изображения из галереи выбираются через multi-select Android photo
picker с ограничением в десять изображений. Они последовательно копируются во
временный private cache и проходят те же ограничения ориентации, 8 MP, 15 MB,
WebP и шифрования; каждая временная копия затем удаляется. При частичной ошибке уже
сохранённые evidence остаются и ставятся на синхронизацию, а UI показывает
точное количество сохранённых фото и не сообщает о полном успехе. Поведение
capture/import принадлежит
[`CameraScreen.kt`](feature-camera/src/main/java/dev/buhanzaz/rwms/worker/feature/camera/CameraScreen.kt)
и
[`CameraViewModel.kt`](feature-camera/src/main/java/dev/buhanzaz/rwms/worker/feature/camera/CameraViewModel.kt).
CameraX-поверхность WorkerApp переносит нужные рабочему элементы
ManagerApp: выбор задней/передней камеры, поддерживаемый optical zoom,
focus/metering, ночной режим, вспышку/фонарь, сетку кадра и экспозицию. После
каждого снимка сразу остаётся live preview без экрана «Переснять/Использовать».
Круглая миниатюра со счётчиком слева снизу открывает листаемую временную галерею,
где отдельный кадр можно удалить; стрелка справа снизу сохраняет всю пачку в
порядке съёмки. При частичной ошибке уже надёжно сохранённые кадры уходят из
временной галереи, повтор отправляет только оставшиеся файлы. Если рабочий
удаляет последний неудачный кадр, стрелка завершает уже сохранённую часть; в
обоих случаях callback завершения получает все evidence ID ровно один раз. Пока
камера открыта, любая аппаратная клавиша громкости делает ровно один снимок на
нажатие; удержание не создаёт дубликаты evidence, а после закрытия камеры
обычная регулировка громкости возвращается. Видимая вкладка Видео намеренно не
создаёт MP4, потому что
публичный worker evidence contract его не принимает.

## Сборка и focused checks

Нужны JDK 17 и установленный Android SDK. Из 'worker-app/':

~~~bash
bash ./gradlew :app:compileDebugKotlin
bash ./gradlew :core-network:testDebugUnitTest --tests 'dev.buhanzaz.rwms.worker.core.network.WorkerGatewayApiContractBoundaryTest'
bash ./gradlew testDebugUnitTest lintDebug assembleDebug
bash ./gradlew -PRWMS_PUBLIC_BASE_URL=https://rwms.example.test assembleDebug
~~~

Для подписанного release храните signing material вне Git и передавайте внешний
properties-файл по образцу [signing.properties.example](signing.properties.example):

~~~bash
bash ./gradlew -PsigningPropertiesFile=/secure/path/rwms-worker-signing.properties assembleRelease
~~~

## Целостность релиза

Публикуйте только точный reviewed APK. Зафиксируйте его package name,
'versionCode', 'versionName', signing certificate и SHA-256, затем установите
этот же файл на device/emulator. End-to-end заявление требует входа через нужный
публичный gateway, первого worker-context request и изменённого task/offline
flow; успешная сборка или HTTP 200 сами по себе недостаточны.

## Известные ограничения

- Текущая Room projection не сохраняет display names qualifications или
  bindings очереди с worker class. Поэтому панель, доступная только по
  qualification, использует авторитетное имя категории и не угадывает название
  роли.
- Текущий worker SSE endpoint воспринимает новую подписку как новое
  invalidation и не реализует пригодный 'Last-Event-ID' replay path. Foreground
  polling сокращает окно устаревания, но не является replay событий.
- Local invalidation row в текущей схеме append-only и не имеют retention/pruning
  policy и индекса '(userId, revision)'. На долго живущих установках могут
  накапливаться лишние локальные данные.
- Baseline-profile module намеренно не активен, пока стабилизируется
  совместимость Android plugin/toolchain; заявления о startup/performance нужно
  измерять на фактической release-конфигурации.

## Источник истины

Публичные worker operations и payload определены в 'contracts/openapi/';
семантика событий — в 'contracts/events/'. Владеющий сервис остаётся
ответственным за авторизацию, переходы задач и recovery-инварианты. Gateway
маршрутизирует и валидирует, но не владеет worker workflow.

Граница worker failures реализована в
[GatewayFailure.kt](core-network/src/main/java/dev/buhanzaz/rwms/worker/core/network/GatewayFailure.kt),
[WorkerSyncCoordinator.kt](core-sync/src/main/java/dev/buhanzaz/rwms/worker/core/sync/WorkerSyncCoordinator.kt)
и [WorkerSyncWork.kt](core-sync/src/main/java/dev/buhanzaz/rwms/worker/core/sync/WorkerSyncWork.kt).
