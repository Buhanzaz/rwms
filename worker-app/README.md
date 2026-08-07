# RWMS Рабочий

`worker-app/` — самостоятельная Android-сборка приватного приложения для рабочих RWMS.
Она не включена в основную Java 25 multi-module сборку репозитория.

## Конфигурация

Приложение обращается только к публичному HTTPS gateway. Обычная debug-сборка
нацелена на действующее тестовое окружение:

```bash
./gradlew assembleDebug
```

По умолчанию используется `https://77-90-158-90.sslip.io`. Другой публичный
HTTPS gateway можно указать явно:

```bash
./gradlew -PRWMS_PUBLIC_BASE_URL=https://rwms.example.org assembleDebug
```

Публичный client — `rwms-worker-android`: Authorization Code + PKCE S256,
scopes `openid profile offline_access worker.tasks`. Пароль не сохраняется,
а browser/deep-link Activity в APK не экспортируется.

Для подписанного release-файла создайте секретный properties-файл вне Git по
примеру [`signing.properties.example`](signing.properties.example):

```bash
./gradlew -PsigningPropertiesFile=/secure/path/rwms-worker-signing.properties assembleRelease
```

## Меню и работы

- Начальный экран — русское меню `Работы`, `Загрузки`, `Профиль`.
- `Работы` строятся только по выданным task-board категориям и их `groupIds`.
  Одна группа образует один столбец, две группы — ровно два. На узком экране
  столбцы идут последовательно, на широком видны одновременно.
- Активное или приостановленное assignment ограничивает карточку назначенной
  группой. Задания без группы показываются секцией `Личные задания` в первом
  групповом столбце; при отсутствии групп используется отдельный столбец.
- KPI-цвет берётся только из `WorkerContext.kpiPalette`, хранится в Room и
  применяется к оставшемуся времени. Локальных green/yellow/red порогов нет.
- В карточке задания показываются стабильный `taskId`, материалы и работы без
  цен, общие фото и отдельные `sourceMediaIds` каждой работы.

## Загрузки и фото результата

`Загрузки` отображают только активные или ошибочные outbox/evidence операции:
статус, сохранённый процент и безопасное описание ошибки. Успешные операции
исчезают автоматически. Кнопка `Повторить` запускает существующий sync.
`encryptedPayload` и `encryptedFilePath` никогда не входят в UI-модель.

После выполнения работы рабочий снимает JPEG и подтверждает его. Evidence
сохраняется зашифрованно, привязывается к заданию и отправляется через
reservation → upload/finalize → `READY`; после этого результат доступен в
приёмке.

Camera UX использует полноэкранный preview, `НОЧЬ` и `ФОТО`, вспышку/фонарь,
tap-to-focus, pinch zoom, сетку, формат кадра, exposure, быстрый режим
движения, переключение камеры и volume shutter. Физическое положение телефона
определяет ориентацию снятого JPEG; до шифрования файл приводится к корректным
пикселям с EXIF `Orientation=1`. Нормализация ограничена 8 МП, чтобы удержать
память процесса и лимит evidence в 15 МБ; Ultra HDR не используется. Стоп 0.6×
показывается только на камере, которая поддерживает такой диапазон. Видимый
пункт `ВИДЕО` не создаёт MP4: worker contract принимает только JPEG evidence,
поэтому приложение остаётся в `ФОТО` и показывает объяснение.

## Безопасность и offline

- AppAuth state шифруется AES-GCM ключом Android Keystore до записи в DataStore.
- Room — единственный UI source of truth. Команда и outbox-row записываются в
  одной транзакции.
- 24-часовой offline lease рассчитывается от server-time/`elapsedRealtime`.
- Sync идёт как unique connected WorkManager job: actions, reservation,
  upload/finalize, ожидание `READY`, затем обновлённый feed.
- FCM содержит только invalidation. `NEW_TASK`, `URGENT_TASK` и
  `TASK_JOIN_AVAILABLE` дают русские generic-уведомления; срочные и смежные
  задания используют high-importance channels.

## Локальные проверки

Используйте JDK 17 и установленный Android SDK:

```bash
ANDROID_HOME=/root/Android/Sdk ANDROID_SDK_ROOT=/root/Android/Sdk \
  ./gradlew testDebugUnitTest lintDebug assembleDebug
```

Compose, Room migration, network compatibility, Camera-настройки и безопасная
проекция загрузок покрыты focused tests. Реальный CameraX hardware, FCM и
аутентифицированный gateway flow дополнительно проверяются на emulator/device.
