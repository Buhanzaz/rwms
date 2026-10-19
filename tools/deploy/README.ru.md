# Интерактивное развертывание RWMS на Ubuntu 24.04

Этот инструмент устанавливает **services + panel/admin/manager + logistics planner** на один VPS. Домен вводится один раз; скрипт согласует HTTPS, OIDC issuer, callbacks, адреса API и пути web-приложений. Секреты вводятся скрыто или генерируются и остаются вне Git.

> **Статус проверки:** выполнены offline-тесты конфигурации, обработки секретов, subprocess/timeout и остановки этапов; core Compose и planner override проверены по Compose JSON Schema. Полная установка на Ubuntu VPS, Docker builds, получение реального сертификата и JVM-тест в этой среде **не выполнены**: отсутствуют Docker/JDK, доступ к registry/Gradle/ACME ограничен. Перед рабочей системой необходим прогон на отдельном чистом VPS. Это не заявление о production-сертификации или высокой доступности.

## 1. Что поддерживается

- Ubuntu **24.04**, архитектура **amd64 / x86_64**, Python 3.10+ (Ubuntu 24.04 поставляет Python 3.12).
- Один прямой публичный IPv4, **назначенный сетевому интерфейсу VPS**; домен с A-записью на него.
- Host Nginx + Let's Encrypt HTTP-01; приложения и инфраструктура в Docker Compose.
- Первая установка на новый сервер; обновление установки, созданной **этим** инструментом.
- Остановка на ошибке, ограниченные ожидания и явный `resume` после диагностики.

**Не поддерживается автоматически:** принятие чужих systemd units/Compose volumes, миграция существующей вручную установленной RWMS, NAT, IPv6/AAAA, Cloudflare proxy, Kubernetes, смена домена, ротация DB credentials, HA, zero-downtime, downgrade БД. Скрипт не переносит действующие данные в новые volumes и не объявляет пустую установку обновлением старой.

Если RWMS уже работает на сервере, **не выбирайте install**. Для существующей не-managed системы сначала нужны инвентаризация units/volumes, backup и отдельный план миграции. `update` без managed state откажется работать.

Это новая topology для нового сервера, а не замена действующей topology. В отличие от ручных host-примеров в [общем runbook](../../docs/deployment.ru.md), внутренние сервисы здесь используют Docker DNS, а необходимые private HTTPS endpoints — host Nginx на bridge IP.

## 2. Что подготовить до запуска

### 2.1. Сервер и DNS

1. Подготовьте чистый Ubuntu 24.04 amd64 VPS и root/sudo-доступ.
2. Минимальная защитная проверка скрипта: **16 GiB RAM и 40 GiB свободного диска**. Это только порог допуска, **не расчет емкости**. Два OSM-региона, Docker build cache, backup и медиа могут потребовать значительно больше. Следите за диском и OOM; для тяжелых нагрузок нужен отдельный sizing.
3. Создайте, например, `wms.company.ru A <IPv4-VPS>`.
4. На первом этапе уберите AAAA и дополнительные A-записи для этого имени. Скрипт требует, чтобы DNS возвращал ровно введенный IPv4.
5. Разрешите входящие TCP **80 и 443** в firewall VPS и у провайдера; сохраните собственный SSH-доступ. Скрипт **не меняет firewall** и не открывает порты БД/MinIO/Kafka наружу.
6. Убедитесь, что исходящий интернет доступен к Ubuntu repositories, Docker Hub/GHCR/MinIO registry, Gradle/Maven, npm, Go modules, PyPI, Geofabrik, Let's Encrypt и выбранным API.
7. Сделайте доступными root процессы Docker. Не выдавайте доступ к docker.sock недоверенным пользователям: он фактически равен root.

Примеры проверок:

```bash
cat /etc/os-release
uname -m
ip -4 address
getent ahostsv4 wms.company.ru
free -h
df -h /
sudo ss -lntp
```

### 2.2. Секреты и внешние credentials

| Что | Зачем | Как предоставляется |
| --- | --- | --- |
| Логин/пароль SYSTEM_ADMIN | Первый вход в panel и admin | Мастер спросит логин; пароль можно ввести или сгенерировать |
| Внутренние DB/OAuth/MinIO/signing secrets | Отдельные учетные записи, M2M и подпись | По умолчанию генерируются независимо; можно выбрать ручной ввод каждого |
| LLM API key + HTTPS base URL + model | Работа assistant-service | Ключ из кабинета провайдера, скрытый ввод; без него полный стек не пройдет стандартный старт |
| Firebase service-account JSON + project_id | Push-уведомления task-board | Существующий секретный файл на сервере с правами 0600 |
| SHA-256 сертификата подписи **WorkerApp release** | Проверка Android App Links gateway | Реальный fingerprint сертификата APK, **не** TLS Let's Encrypt |
| Yandex Geosuggest API key | Подсказки адресов | Необязательно, скрытый ввод |
| Yandex Geocoder API key | Геокодирование адресов | Необязательно, скрытый ввод |
| Yandex Maps v3 browser key | Отображение карты Yandex | Не в этом мастере: после установки через `/admin/logistics` |

**Почему нужны Firebase и WorkerApp fingerprint, если основной запрос — web?** Текущие production guards `task-board-service` и `api-gateway-service` требуют их при запуске полного сервиса. Инструмент не подставляет фальшивые значения, не переключает приложение в `dev` и не отключает защиту. Если этих credentials пока нет, сначала подготовьте их; web-only обход потребовал бы отдельного продуктового изменения.

Файл Firebase не следует присылать в чат или класть в Git. Например, положите его в `/root/rwms-firebase.json`, затем:

```bash
sudo chmod 600 /root/rwms-firebase.json
```

Проверьте `project_id` и права service account в Firebase. Скрипт проверяет структуру/соответствие project_id, но **не доказывает** доставку push; ее нужно проверить отдельным smoke.

Fingerprint WorkerApp имеет формат 32 hex-байта через двоеточие: `AA:BB:...`. Получайте его из **реального release signing certificate**, например командой `keytool -list -v -keystore <существующий-keystore>` на доверенном build-хосте. Не создавайте новый Android signing key ради прохождения формы.

### 2.3. Репозиторий

Используйте один постоянный checkout в `/home/...` или `/opt/...`, без пробелов в пути. Не запускайте скрипт из временного архива, чужого checkout или `curl | bash`.

```bash
cd /opt/rwms   # замените на фактический путь существующего checkout
 git status --short
 git log -1 --oneline
```

Рабочее дерево перед `install/update/resume` должно быть чистым. Скрипт не делает `git pull`, checkout, reset, stash, commit или push. Подготовку ревизии выполняет оператор отдельно, сохраняя пользовательскую работу.

## 3. Первый запуск: пошагово

Все команды ниже выполняются из корня checkout.

### Шаг 1. Посмотреть помощь

```bash
bash tools/deploy/deploy.sh --help
```

`deploy.sh` — небольшой wrapper, запускающий стандартный Python. Логика в `deploy.py`; шаблоны и чистый renderer — `configuration.py`.

### Шаг 2. Создать конфигурацию

```bash
sudo bash tools/deploy/deploy.sh configure
```

Мастер последовательно спросит:

1. **Домен**. Можно указать `wms.company.ru` или `https://wms.company.ru/`. Путь, логин, порт, query, wildcard и IP вместо домена запрещены. IDN преобразуется в punycode.
2. **Публичный IPv4**, назначенный интерфейсу сервера.
3. **Email для ACME** и уведомлений Let's Encrypt. Выполнение `install` означает согласие с условиями Let's Encrypt; команда certbot использует `--agree-tos`.
4. **Логин и пароль администратора**. Пароль минимум 12 символов; Enter генерирует случайное значение.
5. **Внутренние секреты**. Рекомендуемый ответ — `no` на ручной ввод: все генерируются независимо из криптографически случайных байтов, в standard Base64. При `yes` каждый можно ввести дважды или сгенерировать Enter. Минимум 32 печатных ASCII-символа без пробелов; `TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET` дополнительно обязан быть standard Base64 с минимум 32 байтами после декодирования. Enter генерирует совместимое значение.
6. **LLM API**. URL по умолчанию Mistral `/v1`, затем модель и настоящий ключ. Для другого совместимого провайдера укажите его данные.
7. **WorkerApp fingerprint и Firebase JSON/project_id** — обязательные production prerequisites.
8. **Два необязательных ключа Yandex**. Enter оставляет пустыми: Valhalla работает без них, Yandex-поиск адресов — нет.
9. **Интеграцию planner**. `yes` включает demand sync и capacity publication; `no` оставляет planner без M2M-обмена до отдельно согласованного включения.

Конфигурация сохраняется в:

```text
/etc/rwms-managed/system.env
```

Файл получает права **0600**; каталог — **0700**. Секреты не печатаются в терминал. Повторный `configure` **не перезапишет** существующий файл.

Для предварительного заполнения без root можно указать `--env-file /абсолютный/путь/вне/repo/system.env`. Перед production-запуском файл должен принадлежать root и иметь права 0600. Один и тот же `--env-file` затем нужен во всех командах.

### Шаг 3. Проверить план без установки

```bash
sudo bash tools/deploy/deploy.sh plan
```

Показывает домен, issuer, topology, этапы и проценты; **не показывает ключи, не запускает subprocess, не меняет хост и не проверяет DNS/ACME**. Это локальная проверка формы конфигурации, не полный dry-run внешних сервисов.

### Шаг 4. Установить

```bash
sudo bash tools/deploy/deploy.sh install
```

Потребуется набрать точную фразу:

```text
INSTALL wms.company.ru
```

Без интерактивного терминала и подтверждения скрипт не запускается. Установку лучше вести в `tmux`/`screen` на сервере: потеря SSH-соединения не должна выглядеть как успешно завершенный релиз.

Не запускайте второй installer одновременно. Используется эксклюзивный lock.

## 4. Этапы и значение процентов

Процент — **взвешенная доля завершенных этапов**, а не прогноз времени и не точные проценты сетевого скачивания. Переход `25% → 50%` означает границы текущего этапа; завершение отмечается отдельным `✓`.

| № | После завершения | Что делает | Что считается успешным |
| ---: | ---: | --- | --- |
| 1 | 5% | Проверяет ОС/архитектуру, Git, RAM/disk, DNS, interface IP, Firebase, порты и чужие ресурсы | Нет конфликтов и небезопасной подмены существующей установки |
| 2 | 10% | Ставит Docker, Compose v2, Buildx, Nginx, Certbot из Ubuntu; включает службы | Команды завершаются с exit 0 |
| 3 | 20% | Создает private Docker bridge, HTTP-01 webroot, сертификат и timer renew, проверяет Nginx | Certificate создан; `nginx -t` и reload успешны |
| 4 | 25% | Формирует закрытые Compose JSON, policy bucket, SQL migration artifact и Firebase mount | Compose `config --quiet` проходит |
| 5 | 50% | Собирает все Java JAR, Go media, три panel entry points и planner images | Все Docker builds успешны |
| 6 | 55% | Включает HTTP 503 для публичного трафика; при update останавливает writers и делает pg_dump | Writers остановлены; каждый DB dump завершился без ошибок |
| 7 | 65% | Запускает 11 PostgreSQL, Kafka, MinIO; включает bucket versioning; создает topics | Health инфраструктуры и init job успешны |
| 8 | 75% | Загружает два OSM PBF, получает manifest, строит Valhalla и индекс ограничений PostGIS | Download/indexer exit 0, Valhalla `/status` доступен |
| 9 | 80% | На первой установке создает persistent RSA PKCS#12; применяет/проверяет Flyway media | Ключ сохранен, Flyway migrate/validate успешны |
| 10 | 90% | Запускает auth/gateway, затем остальные services; Spring применяет свои Flyway migrations | Каждый HTTP health отвечает успешно |
| 11 | 95% | Запускает planner backend/frontend и panel/admin/manager web server | Три локальных health endpoint доступны |
| 12 | 100% | Проверяет HTTPS routes, OIDC issuer, сохраняет SHA/images receipt, открывает public ingress | Технические smoke завершились без ошибок |

Во время длительных команд каждые несколько секунд выводятся:

```text
[5/12] 25% → 50% · Сборка Java, Go, web и planner образов
  → Build java (точный прогресс — в журнале Docker)
    Журнал: /var/lib/rwms-managed/logs/<release-run>/009.log
    Работа выполняется: 125s; получено 18420 байт вывода.
    Это heartbeat, не гарантия полезного прогресса.
```

- **Heartbeat показывает, что supervisor жив и продолжает ждать**, а не доказывает, что компилятор не застрял.
- Рост числа байт означает новый вывод процесса. Для Docker pull/build и native download подробности/счетчики находятся в журнале.
- Точный процент Docker image pull не складывается в «процент всей системы»: количество слоев и длительность компиляции заранее неизвестны.
- У операций есть timeout: обычно 30 минут, сборки и OSM jobs — до 2 часов каждый, service health — до 10 минут. Это границы ожидания, не SLA.
- На ошибке выводится этап, exit code/timeout и путь журнала. Последующие этапы не запускаются; успешное `100%` не выводится.

**Первое OSM-построение долгое.** Переход на mock/легковой маршрут для ускорения запрещен. Источник `OSM_DATA_VERSION` — hash фактического PBF manifest, не фиктивная дата. Непустые скачанные PBF повторно не скачиваются; обновление набора OSM — отдельная операция, не часть обычного code update.

## 5. Что находится в `.env` и как защищаются секреты

`system.env` имеет явный формат `KEY=<JSON string>`, например для НЕсекретного поля:

```dotenv
DOMAIN="wms.company.ru"
LLM_BASE_URL="https://api.mistral.ai/v1"
LLM_MODEL="mistral-large-latest"
```

Это специальный `.env` мастера. **Не выполняйте `source system.env` и не передавайте его напрямую Docker `--env-file`**: мастер сам разбирает JSON-кавычки, формирует runtime environment и экранирует `$` для Compose. Shell substitutions не исполняются. Дубликаты, неизвестные ключи и управляющие символы отвергаются; `${` в секретах запрещено из-за Spring placeholder resolution.

Основные группы ключей:

- `<SERVICE>_DB_PASSWORD` × 11 и `PLANNER_DB_PASSWORD`;
- `TASK_BOARD_CLIENT_SECRET`, `AUTH_WAREHOUSE_CLIENT_SECRET`, `ASSET_WAREHOUSE_CLIENT_SECRET`, `MAINTENANCE_CLIENT_SECRET`, `INVENTORY_CLIENT_SECRET`, `LOGISTICS_CLIENT_SECRET`, `LOGISTICS_PLANNER_CLIENT_SECRET`;
- `MINIO_ROOT_PASSWORD` и отдельный `MEDIA_MINIO_SECRET_KEY`;
- `AUTH_SIGNING_KEY_STORE_PASSWORD`, `TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET`, `DOSSIER_CURSOR_SECRET`, `CLIENT_PRESENTATION_TOKEN_SECRET`;
- `AUTH_BOOTSTRAP_ADMIN_PASSWORD`, `LLM_API_KEY`, ключи Yandex.

Сервис получает только нужные ему группы, а не все DB-пароли разом. Auth получает service-client secrets для регистрации соответствующих identity. Planner и auth получают одинаковое значение секрета `logistics-planner`, но под своими ожидаемыми именами переменных.

Сгенерированный пароль администратора можно прочитать **локально на доверенном терминале** из закрытого `.env`, например через `sudoedit /etc/rwms-managed/system.env`. Не выводите весь файл в чат, CI или общий терминальный журнал.

Секреты присутствуют в закрытых runtime JSON и Docker environment — root/docker-admin может их прочитать через inspect. Это **не vault и не защита от администратора хоста**. Известные секреты маскируются в installer logs; runtime logs тоже считайте чувствительными и не публикуйте без проверки. Логи и release records имеют ограниченные права. Не включайте `set -x`.

Update проверяет hash всей конфигурации и не принимает измененный `.env`. Это защищает от ситуации «в env новый пароль, а у PostgreSQL в persistent volume старый». Смена API keys/домена/секретов и включение sync после первоначального `no` пока требуют отдельной контролируемой процедуры; не редактируйте hash в state, чтобы обойти проверку.

## 6. Создаваемая topology и файлы

```text
Публичный HTTPS :443
  /                         → web container :18080 → panel
  /admin/                   → admin bundle
  /manager/                 → manager bundle
  /auth/**, /api/**          → gateway :8088
  /auth/callback            → panel callback, не auth upstream
  /logistics-panel/         → planner frontend :5173
  /logistics-panel/api/**   → planner API :8000

Docker network rwms-managed-private (172.21.0.0/16)
  core services → Docker DNS → свои БД, Kafka, owner APIs
  planner backend → logistics-service и auth-service по private DNS
  private Nginx 172.21.0.1:443 → публичная issuer-chain внутри сети
  private Nginx :9443 TLS → token / worker credentials / warehouse
  private Nginx :9444 TLS → MinIO data API
```

Основные services — **контейнеры**, не systemd Java-процессы. Host Nginx и Docker управляются systemd. Все опубликованные Docker ports привязаны к `127.0.0.1`, Kafka наружу не публикуется. Media management остается loopback **внутри media container**, а не публичным host port.

Контейнеры разрешают введенный public domain в bridge IP через собственный `extra_hosts`: HTTPS issuer и сертификат остаются теми же, но внутренние обращения не требуют hairpin через публичный интерфейс. При update private issuer остается доступным, даже когда public IP отвечает 503.

TLS покрывает публичный edge, обязательные private вызовы task-board и MinIO. Остальные container-to-container соединения PostgreSQL/Kafka/HTTP работают в доверенной single-host private network **без TLS/SASL**. Kafka — один broker, replication factor 1, не HA. Для multi-host/недоверенной сети нужна отдельная архитектура и проверка credentials/ACL/TLS.

Создаваемые пути:

| Путь / ресурс | Содержимое |
| --- | --- |
| `/etc/rwms-managed/system.env` | Главная конфигурация, 0600/root |
| `/etc/rwms-managed/auth.p12` | Persistent signing key, 0600 для runtime UID 10001 |
| `/etc/rwms-managed/firebase.json` | Копия service-account JSON для task-board, 0600 для UID 10001 |
| `/etc/nginx/conf.d/rwms-managed.conf` | HTTP/HTTPS и private proxy; access logs отключены, чтобы не писать capability/query secrets |
| `/etc/systemd/system/nginx.service.d/rwms-managed.conf` | Nginx стартует после Docker |
| `/etc/letsencrypt/live/<domain>/` | Сертификат Let's Encrypt |
| `/etc/letsencrypt/renewal-hooks/deploy/rwms-managed.sh` | Проверка и reload Nginx после renew |
| `/var/lib/rwms-managed/state.json` | Последний этап, процент, revision, статус установки |
| `/var/lib/rwms-managed/releases/<tag>/` | Закрытые Compose JSON, SQL для media, OSM manifest, topics, receipt |
| `/var/lib/rwms-managed/logs/<tag-run>/` | Пронумерованные закрытые журналы этапов |
| `/var/lib/rwms-managed/backups/<tag-time>/` | Дополнительные локальные DB dumps при update |
| Docker project `rwms-managed` | Основные сервисы и инфраструктура |
| Docker project `rwms-managed-planner` | Canonical logistics Compose с managed override |

`<tag>` содержит commit SHA и UTC timestamp. Docker images получают release-specific tags; receipt сохраняет image IDs. Release-каталоги — **артефакты и конфигурация, не отдельные source checkouts**.

Не запускать `docker compose up` без списка сервисов на generated core JSON: `keygen`, `media-migrate`, `bucket-init` — служебные jobs, installer запускает их отдельно. Аналогично не запускать tools/test profiles на рабочей БД planner.

## 7. После установки — обязательная ручная приемка

`100%` означает завершение технических этапов, **не полноту бизнес-настроек**.

1. Откройте `https://<domain>/`, войдите первоначальным администратором.
2. Проверьте `/admin/`, `/manager/` с соответствующими entitlement/roles; они используют разные OIDC clients.
3. Проверьте отдельную вкладку `/logistics-panel/`: OIDC должен вернуть ее на этот же путь.
4. Создайте реальные склады, timezone/координаты, пользователей и grants; пустая новая БД не содержит ваших складов или заказов.
5. Настройте транспорт, размеры/нагрузки, водителей, смены и тарифы planner. Без этих фактов truck route может быть отклонен корректно.
6. Проверьте доступ без токена и с запрещенной ролью, refresh/logout, cross-warehouse запреты.
7. Загрузите разрешенное тестовое медиа, дождитесь READY, проверьте чтение без выдачи MinIO credentials.
8. Сделайте тестовый запрос к assistant и проверьте реальные лимиты/баланс LLM API.
9. Проверьте Firebase push на подписанном WorkerApp. Installer не собирает и не публикует APK.
10. Проверьте truck routes, индекс ограничений, изохроны и разрешенную M2M-интеграцию planner на тестовых данных.
11. Настройте мониторинг, внешний backup и log rotation/retention. Installer не устанавливает Prometheus/Grafana и не удаляет старые images/dumps/logs.

`LOGISTICS_CUSTOMER_TEST_CHANGE_PAYMENT_ENABLED=false` задан явно. Customer delivery, реальные депо, payment integration, CAD, загрузочные сайты и Android APK в этот installer не входят; основной набор services/panel/planner собран, но это не автоматическая настройка всех бизнес-интеграций продукта.

## 8. Обновление managed-установки

### До обновления

1. Выберите и проверьте новый commit в **том же checkout**, без незакоммиченных изменений.
2. Сделайте полный внешний backup: 11 БД, PostGIS planner, versioned MinIO objects, Kafka recovery state, `system.env`, Firebase credentials, signing key и конфигурацию. Зафиксируйте OSM manifest. Проверить восстановление нужно в изоляции.
3. Сохраните вне checkout непустой файл-отчет с адресом backup, временем, составом, результатом restore-test и ответственным. **Файл-отчет — подтверждение оператора, не автоматическая проверка backup скриптом**.
4. Согласуйте окно обслуживания. Для базовых приложений будет временный HTTP 503.

```bash
sudo bash tools/deploy/deploy.sh plan
sudo bash tools/deploy/deploy.sh update \
  --backup-reference /root/backups/rwms-restore-check.txt
```

Подтверждение:

```text
UPDATE wms.company.ru
```

Сначала новые images собираются без остановки старых приложений. Далее public ingress закрывается, application writers останавливаются; инфраструктура сохраняется. Делается дополнительный `pg_dump -Fc` каждой БД. После этого выполняются migrations, запуск и smoke, и только затем public traffic открывается.

Автоматические dumps находятся на том же VPS и **не заменяют внешний backup**. Они не включают versioned MinIO, Kafka, private keys и настройки; успешный `pg_dump` не доказывает возможность сквозного восстановления.

Секреты, persistent signing key и volumes сохраняются. Не удаляйте старые images до завершения приемки. При update потерянный signing key не генерируется заново: операция останавливается.

## 9. Если долго работает или завершилось ошибкой

### Посмотреть состояние

```bash
sudo bash tools/deploy/deploy.sh status
```

Поля: `phase`, `tag`, `sha`, `maintenance`, `percent`, `stage`, `log_directory`. Фаза `installing/updating` после завершившегося процесса означает незавершенный релиз, а не успех.

Открыть конкретный installer log из вывода:

```bash
sudo tail -f /var/lib/rwms-managed/logs/<tag-run>/<номер>.log
```

Посмотреть контейнеры и runtime logs, заменив `<tag>` на значение из `status`:

```bash
sudo docker compose --env-file /dev/null -p rwms-managed \
  -f /var/lib/rwms-managed/releases/<tag>/core.json ps -a
sudo docker compose --env-file /dev/null -p rwms-managed \
  -f /var/lib/rwms-managed/releases/<tag>/core.json logs --tail=100 auth-service
sudo docker compose --env-file /dev/null -p rwms-managed-planner \
  -f /var/lib/rwms-managed/releases/<tag>/planner.json logs -f valhalla
```

Не выполняйте `docker compose config` без `--quiet`/закрытого output: resolved configuration содержит credentials.

### Продолжение после исправления причины

1. Изучите конкретный exit code и журналы.
2. При timeout/Ctrl+C проверьте Docker jobs: supervisor завершает свою process group, но Docker daemon/контейнерный job может продолжить работу.
3. Не запускайте конкурирующие миграции и не удаляйте volumes.
4. Сохраните **тот же SHA и `.env`**. `resume` перепроверяет и повторяет последовательность с идемпотентными инфраструктурными операциями, а не прыгает без проверки через сохраненный checkpoint.
5. Выполните:

```bash
sudo bash tools/deploy/deploy.sh resume
```

Подтвердите `RESUME <domain>`. Повторные `migrate` используют штатную историю Flyway/Alembic. Если ошибка произошла **до успешного preflight** и state не создан, исправьте prerequisites и повторите `install`, не `resume`.

| Проблема | Что проверить |
| --- | --- |
| Preflight отказывается из-за старой RWMS | Это защита данных. Нужен план миграции, не удаление state/volumes |
| DNS mismatch | A, отсутствие AAAA, propagation, соответствие IP интерфейсу |
| Certbot HTTP-01 failed | Доступность TCP 80 извне, firewall провайдера, DNS; не повторяйте много раз из-за ACME rate limits |
| Долго на 25–50% | Журнал Docker build, registry/Gradle/npm, RAM и disk; сборка Java ограничена двумя Gradle workers |
| Долго на 65–75% | Download/indexer/Valhalla logs, размеры PBF, диск, CPU; job `Exited (0)` — норма |
| Task-board/gateway не проходят startup | Реальные FCM credentials, Worker certificate fingerprint, Java startup guard logs |
| Media fail-closed | Все SQL migrations, TLS 9444, HTTPS JWKS, bucket versioning, Kafka topics |
| Процент не меняется, байты растут | Один долгий этап; это ожидаемо, см. native log |
| Heartbeat есть, байты не растут | Supervisor жив; процесс может ждать сеть/CPU или зависнуть. Проверить ресурсы и дождаться/разобрать timeout |

### Откат

Автоматического rollback нет: миграции могут сделать предыдущий binary несовместимым. Скрипт не исполняет `downgrade`, `repair`, `baseline`, `clean`, `reset-db`, `down -v` и не удаляет данные.

Если release не прошел после начала maintenance, public ingress остается закрытым. Определите совместимость старых images с текущей схемой и событиями. Возврат images/конфигурации или восстановление backup выполняется отдельно, с согласованием потери новых данных. **Не объявляйте старый tag рабочим, просто изменив state.json.**

## 10. Проверки для разработчика

Без root, Docker, домена или секретов:

```bash
python3 -m unittest discover -s tools/deploy/tests -v
python3 -m py_compile tools/deploy/configuration.py tools/deploy/deploy.py
bash -n tools/deploy/deploy.sh
```

Покрываются нормализация/инъекции домена, права и round-trip `.env`, отказ от shell evaluation, secret redaction, совпадение M2M secrets, URL encoding, обязательные env placeholders, private ports, HTTPS topology, failure/timeout propagation, безвредность `plan`, отказ от чужого state и согласованность Kafka inventory task-board.

Дополнительно на build-хосте с Java 25:

```bash
bash ./gradlew :services:task-board-service:test \
  --tests dev.buhanzaz.rwms.taskboard.config.TaskBoardProductionSafetyValidatorTest \
  --no-daemon --max-workers=2
```

Исправление task-board намеренно узкое: startup guard теперь включает уже существующий канонический `rwms.task-board.driver-shift-owner-proof.v1`, сохраняя запрет на неизвестные дополнительные destinations. Новых событий, scope или миграций эта правка не вводит.

Следующий обязательный acceptance gate — полный чистый install, затем update/resume, reboot и восстановление backup на отдельном Ubuntu 24.04 VPS с реальным DNS/TLS. Не заменять его успешными offline-тестами.
