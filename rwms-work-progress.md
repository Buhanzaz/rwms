# RWMS — выполнение исправлений аудита

Основание: [полный аудит](rwms-work.md) и команда пользователя от 6 сентября 2026 года исправить находки, улучшать код и использовать агентов. Исходная ревизия перед исправлениями: `aa148f5aa5c7d347f6afca9ec67ba8991d897e21`.

UI и UX меняет только основной исполнитель. Он же проверяет весь diff, принимает результаты агентов и делает коммиты. Агенты получают ограниченную область файлов; новые изменения начинаются после проверки и коммита предыдущего шага в том же основном worktree.

Дополнительное прямое правило пользователя: устаревшие данные и код не требуют совместимости. Историческое поведение само по себе не является основанием сохранять поля, обходы или отдельные ветки реализации; решения опираются на действующие контракты и необходимое поведение продукта.

Порядок: High → Medium с зависимыми контрактами и consumers → Low и локальный рефакторинг → итоговая проверка. Новые подтверждённые проблемы добавляются отдельными строками; размер файла или само совпадение строк не требует механического переписывания.

Каждая завершённая строка содержит фактически выполненную проверку. Коммит, изменивший её статус, является записью завершения. Публикация не запрошена; исходные исправления и состояние уже запущенного runtime учитываются отдельно.

## Состояние

- Исходных находок: 135 (High: 1, Medium: 38, Low: 96).
- Исправления исходников проверены: 2.
- Требуют отдельной публикации и изменения runtime: R-095.
- Следующий шаг: R-006.

| ID | Приоритет | Находка | Статус / проверка |
| --- | --- | --- | --- |
| [R-001](rwms-work.md#r-001) | Medium | SQL-граница logistics-service расходится с исполняемой архитектурной политикой | Ожидает |
| [R-002](rwms-work.md#r-002) | Low | JPA-сущность сохраняет перечисления из контейнера HTTP DTO | Ожидает |
| [R-003](rwms-work.md#r-003) | Low | Документация неверно назначает владельца throttling регистрации | Ожидает |
| [R-004](rwms-work.md#r-004) | Low | Manager пропускает LOST в словаре исключаемых статусов сметы | Ожидает |
| [R-005](rwms-work.md#r-005) | Low | Общие инструкции контрактов неполно описывают действующий V2 envelope | Ожидает |
| [R-006](rwms-work.md#r-006) | Medium | Планирование создаёт отгрузки до обязательной по контракту регистрации смены | Ожидает |
| [R-007](rwms-work.md#r-007) | Medium | Планировщик отвергает допустимые у владельца имена контактов длиннее 200 символов | Ожидает |
| [R-008](rwms-work.md#r-008) | Medium | Ограничение одной команды ошибочно применено ко всей дневной выдаче статусов | Ожидает |
| [R-009](rwms-work.md#r-009) | Medium | Rental Manager отправляет неверное имя поля в запросе тарифов | Исправлено: каноническое rentalItemIds; RentalPricingApiTest — 6/6, без пропусков. |
| [R-010](rwms-work.md#r-010) | Medium | Android Manager не передаёт приоритет продолжения активного ремонта при приёмке перемещения | Ожидает |
| [R-011](rwms-work.md#r-011) | Low | Тип Panel пропускает допустимое состояние генерации NOT_REQUIRED | Ожидает |
| [R-012](rwms-work.md#r-012) | Medium | Факт начала отмены перемещения отсутствует в схеме и попадает в dossier DLT | Ожидает |
| [R-013](rwms-work.md#r-013) | Medium | Канонический auth OpenAPI не описывает 14 действующих REST-операций | Ожидает |
| [R-014](rwms-work.md#r-014) | Medium | Maintenance snapshot требует number у потребителя, но запрещает его в схеме | Ожидает |
| [R-015](rwms-work.md#r-015) | Medium | Схема регистрации внешней задачи запрещает действующий plannerLineage | Ожидает |
| [R-016](rwms-work.md#r-016) | Low | Контракт доски обещает audienceSelectors, которых нет во входном и выходном DTO | Ожидает |
| [R-017](rwms-work.md#r-017) | Low | Ответ повторной постановки asset outbox не соответствует канонической форме | Ожидает |
| [R-018](rwms-work.md#r-018) | Low | Схема readiness мебели запрещает три реально возвращаемых флага | Ожидает |
| [R-019](rwms-work.md#r-019) | Low | Схема выбора в презентации не допускает реализованный срок аренды отдельной бытовки | Ожидает |
| [R-020](rwms-work.md#r-020) | Low | Три старых Go-обёртки событий не имеют вызовов | Ожидает |
| [R-021](rwms-work.md#r-021) | Low | Компонент предпросмотра грузовика и калькулятор недостижимы | Ожидает |
| [R-022](rwms-work.md#r-022) | Low | SwitchField и пять связанных CSS-правил не используются | Ожидает |
| [R-023](rwms-work.md#r-023) | Low | Три TS-экспорта планировщика не имеют потребителей | Ожидает |
| [R-024](rwms-work.md#r-024) | Low | Python ETA-адаптер и два его DTO не вызываются | Ожидает |
| [R-025](rwms-work.md#r-025) | Low | Функция поиска truck capability не используется | Ожидает |
| [R-026](rwms-work.md#r-026) | Low | Документированная PLANNER_DEFAULT_SEED не влияет на работу | Ожидает |
| [R-027](rwms-work.md#r-027) | Low | Девять приватных Java-методов не имеют потребителей | Ожидает |
| [R-028](rwms-work.md#r-028) | Low | Reconciliation coordinator получает неиспользуемый репозиторий | Ожидает |
| [R-029](rwms-work.md#r-029) | Low | Три тестовые reference policy упаковываются в runtime starter | Ожидает |
| [R-030](rwms-work.md#r-030) | Low | Старые страницы driver/logistics board недостижимы | Ожидает |
| [R-031](rwms-work.md#r-031) | Low | Старый экран задач логистики и его API-клиент недостижимы | Ожидает |
| [R-032](rwms-work.md#r-032) | Low | Локальные operation-contract константы проверяют только сами себя | Ожидает |
| [R-033](rwms-work.md#r-033) | Low | Hook, порт и адаптер media preferences не подключены | Ожидает |
| [R-034](rwms-work.md#r-034) | Low | Старый разрешающий guard возврата оборудования не вызывается | Ожидает |
| [R-035](rwms-work.md#r-035) | Low | Диалог добавления содержимого не подключён | Ожидает |
| [R-036](rwms-work.md#r-036) | Low | Карточка настроек планирования инвентаризации не подключена | Ожидает |
| [R-037](rwms-work.md#r-037) | Low | Старый диалог пароля не подключён | Ожидает |
| [R-038](rwms-work.md#r-038) | Low | Неиспользуемые объявления: panel/src/components/grid-sort-button.tsx | Ожидает |
| [R-039](rwms-work.md#r-039) | Low | Неиспользуемые объявления: panel/src/features/auth/auth-config.ts | Ожидает |
| [R-040](rwms-work.md#r-040) | Low | Неиспользуемые объявления: panel/src/features/inventory/api/inventory-api.ts | Ожидает |
| [R-041](rwms-work.md#r-041) | Low | Неиспользуемые объявления: panel/src/features/inventory/domain/inventory-domain.ts | Ожидает |
| [R-042](rwms-work.md#r-042) | Low | Неиспользуемые объявления: panel/src/features/inventory/inventory-access.ts | Ожидает |
| [R-043](rwms-work.md#r-043) | Low | Неиспользуемые объявления: panel/src/features/inventory/model/inventory-service.ts | Ожидает |
| [R-044](rwms-work.md#r-044) | Low | Неиспользуемые объявления: panel/src/features/logistics/returns/api.ts | Ожидает |
| [R-045](rwms-work.md#r-045) | Low | Неиспользуемые объявления: panel/src/features/media/media-preview-cache.ts | Ожидает |
| [R-046](rwms-work.md#r-046) | Low | Неиспользуемые объявления: panel/src/features/media/model/media.ts | Ожидает |
| [R-047](rwms-work.md#r-047) | Low | Неиспользуемые объявления: panel/src/features/orders/api/orders-api.ts | Ожидает |
| [R-048](rwms-work.md#r-048) | Low | Неиспользуемые объявления: panel/src/features/orders/domain/orders.ts | Ожидает |
| [R-049](rwms-work.md#r-049) | Low | Неиспользуемые объявления: panel/src/features/rental-items/model/rental-item.ts | Ожидает |
| [R-050](rwms-work.md#r-050) | Low | Неиспользуемые объявления: panel/src/features/repair-estimates/api/http-maintenance-lifecycle-client.ts | Ожидает |
| [R-051](rwms-work.md#r-051) | Low | Неиспользуемые объявления: panel/src/features/repair-estimates/api/repair-estimates-api.ts | Ожидает |
| [R-052](rwms-work.md#r-052) | Low | Неиспользуемые объявления: panel/src/features/repair-tasks/api/repair-tasks-api.ts | Ожидает |
| [R-053](rwms-work.md#r-053) | Low | Неиспользуемые объявления: panel/src/features/repair-tasks/domain/repair-task-domain.ts | Ожидает |
| [R-054](rwms-work.md#r-054) | Low | Неиспользуемые объявления: panel/src/features/settings/estimates-repairs/api/repair-estimate-catalog-canvas-settings-api.ts | Ожидает |
| [R-055](rwms-work.md#r-055) | Low | Неиспользуемые объявления: panel/src/features/task-board/domain/task-board-domain.ts | Ожидает |
| [R-056](rwms-work.md#r-056) | Low | Неиспользуемые объявления: cabin-cad/src/cad/commands/components.ts | Ожидает |
| [R-057](rwms-work.md#r-057) | Low | Неиспользуемые объявления: cabin-cad/src/cad/commands/moveModule.ts | Ожидает |
| [R-058](rwms-work.md#r-058) | Low | Неиспользуемые объявления: cabin-cad/src/cad/commands/shared.ts | Ожидает |
| [R-059](rwms-work.md#r-059) | Low | Неиспользуемые объявления: cabin-cad/src/cad/commands/stairs.ts | Ожидает |
| [R-060](rwms-work.md#r-060) | Low | Неиспользуемые объявления: cabin-cad/src/cad/constraints/openings.ts | Ожидает |
| [R-061](rwms-work.md#r-061) | Low | Неиспользуемые объявления: cabin-cad/src/cad/domain/floors.ts | Ожидает |
| [R-062](rwms-work.md#r-062) | Low | Неиспользуемые объявления: cabin-cad/src/cad/frame/frameMaterials.ts | Ожидает |
| [R-063](rwms-work.md#r-063) | Low | Неиспользуемые объявления: cabin-cad/src/cad/selectors/documentSelectors.ts | Ожидает |
| [R-064](rwms-work.md#r-064) | Low | Неиспользуемые объявления: cabin-cad/src/diagnostics/runtimeDiagnostics.ts | Ожидает |
| [R-065](rwms-work.md#r-065) | Low | Неиспользуемые объявления: cabin-cad/src/master-template/domain/model.ts | Ожидает |
| [R-066](rwms-work.md#r-066) | Low | Неиспользуемые объявления: cabin-cad/src/master-template/domain/validation.ts | Ожидает |
| [R-067](rwms-work.md#r-067) | Low | Неиспользуемые объявления: cabin-cad/src/master-template/persistence.ts | Ожидает |
| [R-068](rwms-work.md#r-068) | Low | Неиспользуемые Manager inventory policy helpers | Ожидает |
| [R-069](rwms-work.md#r-069) | Low | Неиспользуемый экран Worker и sync-status banner | Ожидает |
| [R-070](rwms-work.md#r-070) | Low | Неиспользуемые методы local store/DAO Worker | Ожидает |
| [R-071](rwms-work.md#r-071) | Low | Неиспользуемые методы local store/DAO Driver | Ожидает |
| [R-072](rwms-work.md#r-072) | Low | Неиспользуемые explicit imports: app | Ожидает |
| [R-073](rwms-work.md#r-073) | Low | Неиспользуемые explicit imports: client-app | Ожидает |
| [R-074](rwms-work.md#r-074) | Low | Неиспользуемые explicit imports: worker-app | Ожидает |
| [R-075](rwms-work.md#r-075) | Low | Неиспользуемые explicit imports: driver-app | Ожидает |
| [R-076](rwms-work.md#r-076) | Medium | Таймаут заголовков SSE не отменяет исходящий HTTP exchange | Ожидает |
| [R-077](rwms-work.md#r-077) | Medium | Чтение SSE от LLM не имеет действующего таймаута после получения заголовков | Ожидает |
| [R-078](rwms-work.md#r-078) | Medium | Замена маршрута до начала работ обходит проверку назначения очереди | Ожидает |
| [R-079](rwms-work.md#r-079) | Medium | Inventory outbox и sanitized DLT повторяют публикацию без конечного лимита | Ожидает |
| [R-080](rwms-work.md#r-080) | Medium | Inquiry outbox не сохраняет прогресс retry при исключении и не завершает повторные попытки | Ожидает |
| [R-081](rwms-work.md#r-081) | Medium | Одновременное закрытие SSE удаляет только что добавленную подписку | Ожидает |
| [R-082](rwms-work.md#r-082) | Medium | Customer: повторный вход во время старого polling-запроса пропускает загрузку нового сеанса | Ожидает |
| [R-083](rwms-work.md#r-083) | Medium | Rental Manager: позднее сохранение заказа A подменяет открытую карточку B | Ожидает |
| [R-084](rwms-work.md#r-084) | Medium | Panel: частичный успех пакетной загрузки теряет загруженные media IDs и блокирует готовность | Ожидает |
| [R-085](rwms-work.md#r-085) | Medium | CAD: поздний импорт заменяет более новый проект и стирает его undo-историю | Ожидает |
| [R-086](rwms-work.md#r-086) | Low | CAD: окончание импорта текстуры отменяет выбранное пользователем наследование материала | Ожидает |
| [R-087](rwms-work.md#r-087) | Medium | Driver: запоздавший GET снимка смены откатывает уже подтверждённую версию в Room | Ожидает |
| [R-088](rwms-work.md#r-088) | Medium | Manager: ошибка записи очереди при «Повторить загрузку» выходит необработанной из ViewModel | Ожидает |
| [R-089](rwms-work.md#r-089) | Low | Worker: старое обновление профиля перезаписывает только что загруженный аватар | Ожидает |
| [R-090](rwms-work.md#r-090) | Medium | Поздний успех публикации capacity снимает обязательство отправить новую генерацию | Ожидает |
| [R-091](rwms-work.md#r-091) | Medium | Capacity и contractor recovery ограничивают backoff, но не количество попыток | Ожидает |
| [R-092](rwms-work.md#r-092) | Medium | Media import теряет собственный lease при допустимо медленном preflight и обходит MaxAttempts | Ожидает |
| [R-093](rwms-work.md#r-093) | Medium | Поздний resolve адреса подменяет более новый адрес проверки слотов | Ожидает |
| [R-094](rwms-work.md#r-094) | Medium | Время прибытия и остановок слота форматируется в часовом поясе браузера | Ожидает |
| [R-095](rwms-work.md#r-095) | High | Публичный auth runtime включает фиксированный секрет service-клиента из репозитория | Исходники исправлены: auth 87/87, task-board 14/14, без пропусков. Runtime ещё требует публикации и ротации. |
| [R-096](rwms-work.md#r-096) | Medium | За Nginx все клиенты расходуют один source-бюджет регистрации | Ожидает |
| [R-097](rwms-work.md#r-097) | Medium | Публичная проверка пароля при входе не имеет лимита попыток | Ожидает |
| [R-098](rwms-work.md#r-098) | Medium | Список пользователей делает три дополнительных чтения на каждую запись | Ожидает |
| [R-099](rwms-work.md#r-099) | Medium | Разрешённый API пароль превышает byte-limit настроенного BCrypt | Ожидает |
| [R-100](rwms-work.md#r-100) | Medium | Nginx access log сохраняет действующие capability tokens из URL | Ожидает |
| [R-101](rwms-work.md#r-101) | Medium | Пагинация смет выполняется после загрузки всех смет и всех ревизий склада | Ожидает |
| [R-102](rwms-work.md#r-102) | Medium | Доска водителей обогащает все исторические рейсы до отбора видимых карточек | Ожидает |
| [R-103](rwms-work.md#r-103) | Low | Каждая публичная фотография повторно строит всю презентацию и читает equipment availability | Ожидает |
| [R-104](rwms-work.md#r-104) | Low | Manager: накопление media cache делает каждое чтение полным обходом растущего каталога | Ожидает |
| [R-105](rwms-work.md#r-105) | Low | Customer: удалённые из формы вложения остаются в приватном cache | Ожидает |
| [R-106](rwms-work.md#r-106) | Medium | Customer: импорт до 20 вложений копирует файловые потоки в UI-потоке | Ожидает |
| [R-107](rwms-work.md#r-107) | Medium | CAD: STEP tessellation выполняется синхронно в главном потоке, лимиты результата проверяются после неё | Ожидает |
| [R-108](rwms-work.md#r-108) | Low | Panel: каждая видимая карточка сразу загружает все до 100 cabin previews без общей очереди | Ожидает |
| [R-109](rwms-work.md#r-109) | Low | Panel: SSE чтение диалога остаётся жить после ухода с его экрана | Ожидает |
| [R-110](rwms-work.md#r-110) | Medium | Ingestion traceback выводит чувствительный upstream payload при ошибке валидации | Ожидает |
| [R-111](rwms-work.md#r-111) | Medium | План одного дня материализует всю retained историю заявок, задач и смен склада | Ожидает |
| [R-112](rwms-work.md#r-112) | Low | Проверки HTTP parity не сопоставляют сериализованный DTO с канонической схемой | Ожидает |
| [R-113](rwms-work.md#r-113) | Low | Auth UI не имеет исполняемых тестов подготовки CSRF и блокировки отправки формы | Ожидает |
| [R-114](rwms-work.md#r-114) | Low | CAD тест wiring видимости проверяет только HTML и не выполняет обработчики | Ожидает |
| [R-115](rwms-work.md#r-115) | Low | Manager command coordinators не покрыты исполнением orchestration в тестах | Ожидает |
| [R-116](rwms-work.md#r-116) | Low | Customer checkout/workflow ViewModel не исполняется в тестах | Ожидает |
| [R-117](rwms-work.md#r-117) | Low | Rental Manager сохранение idempotency key в SavedStateHandle не проверяется через ViewModel | Ожидает |
| [R-118](rwms-work.md#r-118) | Low | Worker TaskDetail переход от UI action к локальному outbox не покрыт ViewModel-тестом | Ожидает |
| [R-119](rwms-work.md#r-119) | Low | Driver TaskDetail построение команды и optimistic action не исполняется в тестах | Ожидает |
| [R-120](rwms-work.md#r-120) | Low | Два Driver Shift presentation tests проверяют свои fixtures и повторённое условие вместо production UI | Ожидает |
| [R-121](rwms-work.md#r-121) | Low | Четыре адаптера logistics frontend по-разному читают Problem Details | Ожидает |
| [R-122](rwms-work.md#r-122) | Low | Go API-тесты driver shift не различают методы чтения доказательств разных владельцев | Ожидает |
| [R-123](rwms-work.md#r-123) | Low | Два теста исторического backfill проверяют строки миграции вместо результата upgrade | Ожидает |
| [R-124](rwms-work.md#r-124) | Low | Планировщик повторно реализует существующий предикат режима дня | Ожидает |
| [R-125](rwms-work.md#r-125) | Low | Названия статусов приёмки продублированы в фильтре и badge | Ожидает |
| [R-126](rwms-work.md#r-126) | Low | Два диалога перемещения наполнения дублируют одну presentation workflow policy | Ожидает |
| [R-127](rwms-work.md#r-127) | Low | Правило конфликтов inventory повторено в validation и read projection | Ожидает |
| [R-128](rwms-work.md#r-128) | Low | Два inventory support повторяют построение session event payload | Ожидает |
| [R-129](rwms-work.md#r-129) | Low | Три task-board сервиса повторяют сборку registration response | Ожидает |
| [R-130](rwms-work.md#r-130) | Low | Два logistics asset client повторяют decoder equipment reservation | Ожидает |
| [R-131](rwms-work.md#r-131) | Low | План и расчёт слотов повторяют mapping физических параметров тягача/прицепа | Ожидает |
| [R-132](rwms-work.md#r-132) | Low | Обычный и recovery replan повторяют преобразование задержанных смен | Ожидает |
| [R-133](rwms-work.md#r-133) | Low | Два media upload lock метода повторяют жизненный цикл advisory lock | Ожидает |
| [R-134](rwms-work.md#r-134) | Low | Чтение и закрытие planning day повторяют одну status projection | Ожидает |
| [R-135](rwms-work.md#r-135) | Low | Retry и replacement media import повторяют решение о replay receipt | Ожидает |

## Завершённые шаги

1. **R-095 — внешние credentials и безопасная ротация task-board.** Убраны fallback-секреты, оба сервиса отклоняют прежний секрет, auth проверяет точный контракт клиента и повышенную revision. При смене секрета сохранённые авторизации отзываются атомарно; повторный запуск сохраняет новые. Публичный issuer с development credentials блокирует запуск; существующие переменные окружения позволяют настроить безопасный публичный dev-профиль. Выполнены 86 auth и 14 task-board тестов, включая PostgreSQL rotation и HTTP token endpoint; ошибок и пропусков нет. Команды и XML проверены отдельным агентом, полный diff — основным исполнителем. Runtime не публиковался: новый секрет нужно согласованно установить у auth и task-board, а уже выданные JWT действуют до собственного `exp`.
2. **R-009 — канонический запрос тарифов Rental Manager.** DTO сериализует `rentalItemIds`, как требуют OpenAPI и request владельца. Обновлён существующий тест реального Retrofit/Moshi HTTP-запроса. Все шесть тестов `RentalPricingApiTest` прошли без пропусков; выполнена компиляция Android debug/test исходников. Проверка использовала Gradle 9.5.1, Java 17 и SDK 36; APK не публиковался.
3. **R-095 — дополнительная проверка префикса gateway.** `AuthGatewayPrefixIntegrationTest` использует loopback issuer, допустимый для его dev-credentials. Проверки discovery, однократного `/auth` prefix, forwarded redirect/login/logout и упакованного UI сохранены и прошли: 1/1 без пропусков. Общий результат R-095 — 87 auth и 14 task-board тестов; production guard не ослаблялся.

## Дополнительные находки

Пока нет.
