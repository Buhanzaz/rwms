# RWMS

[English version](README.md)

RWMS — система управления складскими и арендными операциями. Она объединяет
идентичность складов и доступ, кабины и оборудование, операционные очереди,
ремонт, инвентаризацию, логистику, медиа, read-проекции, аналитику и ассистента
через независимо владеющие данными сервисы и три активных клиента.

Этот репозиторий содержит действующий продукт. Исторические планы, браузерные
моки, старые выгрузки БД и прежняя панель служат только свидетельствами: они не
являются runtime-источниками истины и не задают текущую последовательность
релизов.

## Почему система разделена именно так

В RWMS есть несколько потоков с разной скоростью изменений и разными границами
согласованности. Поэтому каждый домен владеет своим состоянием и переходами, а
клиенты видят одну публичную границу:

```text
panel / manager Android / worker Android
                    |
                    v
          stateless API gateway
                    |
       +------------+-------------+
       |                          |
       v                          v
  auth-service             public domain APIs
                                  |
                         service-owned PostgreSQL
                                  |
                     transactional outbox / Kafka
                                  |
                   другие владельцы и read-проекции

media-service дополнительно владеет приватными объектами MinIO и производными.
```

Такое разделение сохраняет четыре важных свойства:

- у каждого бизнес-инварианта и статуса ровно один сервис-владелец;
- каждый stateful-сервис владеет одной PostgreSQL БД и своей историей Flyway;
- междоменные связи используют версионированные HTTP- или event-контракты, а не
  общие таблицы или Java-модели домена;
- gateway остаётся транспортной и security-границей, а не скрытым бизнес-
  оркестратором.

## Активные компоненты

| Компонент | Runtime-форма | Ответственность |
| --- | --- | --- |
| [`panel/`](panel/) | Web-клиент React/TypeScript | Основная панель менеджеров и операций |
| [`app/`](app/) | Android-клиент менеджера | Мобильные сценарии менеджера |
| [`worker-app/`](worker-app/) | Android-клиент работника | Вход работника, назначения, исполнение задач и съёмка медиа |
| [`worker-download-site/`](worker-download-site/) | Исходники static release-сайта, не развёрнуты | Страница загрузки Worker APK; текущий manifest pending и APK не выдаёт |
| [`api-gateway-service`](services/api-gateway-service/) | Stateless Spring edge | Публичные `/auth/**` и `/api/**`, edge-политика JWT, ограниченные стримы и transport failures |
| [`auth-service`](services/auth-service/) | Stateful Spring-сервис | OIDC/OAuth2, пользователи, работники, роли, клиенты и доступ к складам |
| [`warehouse-service`](services/warehouse-service/) | Stateful Spring-сервис | Идентичность, lifecycle, метаданные и effective-dated timezone склада |
| [`asset-service`](services/asset-service/) | Stateful Spring-сервис | Кабины, оборудование, остатки, holds, leases и asset-факты |
| [`task-board-service`](services/task-board-service/) | Stateful Spring-сервис | Очереди, workforce, назначения, task board и worker stream |
| [`maintenance-service`](services/maintenance-service/) | Stateful Spring-сервис | Каталог, сметы, ремонт, приёмка и решения о списании |
| [`inventory-service`](services/inventory-service/) | Stateful Spring-сервис | Сессии инвентаризации, находки, сверка, завершение и публикация |
| [`logistics-service`](services/logistics-service/) | Stateful Spring-сервис | Запросы аренды, возвраты, отгрузки, перемещения, водители и логистические саги |
| [`media-service`](services/media-service/) | Stateful Go-сервис | Метаданные медиа, загрузки, оригиналы, производные и приватный доступ MinIO |
| [`dossier-service`](services/dossier-service/) | Stateful Spring read model | Междоменная проекция активности кабины |
| [`analytics-service`](services/analytics-service/) | Stateful Spring read model | KPI и dashboard-проекции |
| [`assistant-service`](services/assistant-service/) | Stateful Spring-сервис | Диалоги ассистента и история tool calls; аренда остаётся во владении logistics |
| [`platform/`](platform/) | Java-библиотеки и policy tests | Framework-neutral технические контракты, общая Spring-инфраструктура и архитектурные проверки |

Авторитетная таблица владельцев и доказательства границ находятся в
[`docs/project-knowledge/architecture.md`](docs/project-knowledge/architecture.md).

## Как проходят запросы и факты

1. Интерактивный клиент получает OIDC-сессию по Authorization Code с PKCE и
   обращается только к настроенному публичному gateway.
2. Gateway проверяет публичный маршрут и Bearer-токен, удаляет небезопасные
   forwarding metadata и передаёт запрос одному сервису-владельцу.
3. Владелец повторно проверяет токен как resource server, применяет складскую и
   доменную авторизацию, проверяет optimistic concurrency или idempotency и
   фиксирует собственную транзакцию.
4. Если факт нужен другим доменам, та же транзакция записывает
   версионированный outbox. Ограниченный relay публикует запись в Kafka.
5. Consumer дедуплицирует событие через inbox, проверяет порядок агрегата и
   версии и изменяет только своё состояние или read-проекцию.
6. SSE-сообщения являются transport- или invalidation-сигналами, если контракт
   явно не объявляет полную проекцию. Клиенты обновляют только затронутый кэш.

Межсервисный workflow хранится инициирующим доменом как saga или reconciliation
work. Браузер никогда не координирует согласованность, Kafka не является БД, а
межсервисного 2PC нет.

## Источники истины

Используйте следующий порядок авторитета:

1. текущая продуктовая задача и [`AGENTS.md`](AGENTS.md);
2. канонические OpenAPI- и event-контракты в [`contracts/`](contracts/);
3. доменный код, Flyway-миграции и тесты сервиса-владельца;
4. поддерживаемая карта в
   [`docs/project-knowledge/`](docs/project-knowledge/);
5. исторические планы и старые реализации только как свидетельства.

Конфликт о владельце, идентичности, статусе, деньгах, времени или разрушительной
работе с данными нельзя разрешать догадкой. Зафиксируйте точные доказательства и
получите минимально необходимое продуктовое решение.

## Структура репозитория

| Путь | Назначение |
| --- | --- |
| [`contracts/openapi/`](contracts/openapi/) | Канонические публичные и внутренние HTTP-границы |
| [`contracts/events/`](contracts/events/) | Канонические event envelopes, схемы и AsyncAPI-описания |
| [`services/`](services/) | Независимо владеющие данными Spring- и Go-deployables |
| [`platform/`](platform/) | Технические Java-библиотеки и architecture-policy tests |
| [`panel/`](panel/) | Основная web-панель |
| [`app/`](app/) | Android-приложение менеджера |
| [`worker-app/`](worker-app/) | Android-приложение работника |
| [`worker-download-site/`](worker-download-site/) | Исходники static-страницы загрузки Worker APK; текущий релиз pending |
| [`docs/project-knowledge/`](docs/project-knowledge/) | Поддерживаемая навигация по архитектуре и домену |
| [`docs/reviews/`](docs/reviews/) | Аудиты с доказательствами и планы исправлений |
| [`compose.yaml`](compose.yaml) | Только локальные/test-зависимости, не production orchestration |

## Локальная разработка

Корневой Compose запускает только изолированные dev-зависимости: отдельную
PostgreSQL БД для каждого stateful-сервиса, Kafka, MinIO и явно отделённый
RabbitMQ-профиль совместимости media. Он не запускает и не развёртывает
продуктовые сервисы.

```bash
docker compose --profile core up -d
```

Запускайте только нужные для сценария сервисы с их `dev`-профилями и переменными
из локальных README. Обычный браузерный поток включает auth, сервисы-владельцы,
gateway и затем Vite:

```bash
bash ./gradlew :services:auth-service:bootRun --args='--spring.profiles.active=dev'
bash ./gradlew :services:api-gateway-service:bootRun --args='--spring.profiles.active=dev'
npm --prefix panel run dev
```

Нельзя переносить dev-учётные данные, loopback URL, автоматическое создание
топиков или Compose-настройки в production.

## Проверка

Запускайте самые узкие проверки, покрывающие изменение. Общие policy gates:

```bash
bash ./gradlew verifyApprovedDependencyVersions
bash ./gradlew verifyCanonicalContracts
bash ./gradlew :platform:architecture-tests:test
npm --prefix panel run typecheck
```

Focused-команды приведены в README каждого сервиса и клиента. Изменение
контракта также требует root canonical-contract gate и focused
producer/consumer compatibility; для Android-контракта нужно собрать и
проверить именно передаваемый APK.

## Правила безопасного изменения

1. До изменения потока определите владельца и канонический контракт.
2. Проследите все активные клиенты, gateway routes, producers, consumers,
   persistence, cache invalidation, авторизацию и восстановление после ошибок.
3. Меняйте границу одновременно со всеми активными producers/consumers.
4. Оставляйте состояние и оркестрацию у владельца; не создавайте browser- или
   gateway-саги.
5. Сохраняйте неизменность Flyway, гарантии outbox/inbox, optimistic fencing,
   idempotency и складскую изоляцию.
6. Удаляйте заменённый runtime path в том же ограниченном изменении, но никогда
   не удаляйте live-данные или незакоммиченную работу другой задачи без явного
   разрешения.
7. Обновляйте поддерживаемую карту знаний при изменении владельца, архитектуры,
   устойчивого инварианта или контракта.

## Состояние архитектуры

Полный текущий аудит, подтверждённые слабые места, доказательства и
последовательный remediation backlog находятся в
[`docs/reviews/20260808-full-architecture-audit.md`](docs/reviews/20260808-full-architecture-audit.md).
Структурная инвентаризация god-классов, метрики разбиения, граница сохранённого
поведения и доказательства проверок поддерживаются в
[`docs/reviews/20260808-god-class-decomposition.md`](docs/reviews/20260808-god-class-decomposition.md).
Контракт документации для будущих README и исходного кода описан в
[`docs/project-knowledge/documentation-standard.md`](docs/project-knowledge/documentation-standard.md).

## Основные материалы

- [Текущая архитектура](docs/project-knowledge/architecture.md)
- [Текущий каталог компонентов](docs/project-knowledge/service-catalog.md)
- [Сквозные runtime-потоки](docs/project-knowledge/runtime-flows.md)
- [Полный цикл бытовки и варианты](docs/project-knowledge/cabin-lifecycle.ru.md)
- [Подтверждённая доменная логика](docs/project-knowledge/domain-logic.md)
- [Индекс контрактов и правила эволюции](docs/project-knowledge/contracts.md)
- [Открытые вопросы](docs/project-knowledge/open-questions.md)
- [Реестр сервисов](services/README.md)
- [Правила разработки и безопасности рабочего дерева](AGENTS.md)
