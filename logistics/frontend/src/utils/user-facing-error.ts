import { ApiError } from '../api/client';

/** Stable operator-facing explanation with all transport diagnostics removed. */
export interface UserFacingError {
  title: string;
  detail: string;
}

const domainMessages: Readonly<Record<string, UserFacingError>> = {
  MANUAL_CHANGE_INVALID: {
    title: 'Изменение не помещается в план',
    detail: 'После изменения водитель не успеет выполнить рейсы в рабочее время и заданные окна. Измените время, состав рейса или водителя.',
  },
  NO_FEASIBLE_CYCLE: {
    title: 'Рейс нельзя добавить',
    detail: 'График водителя на выбранное время уже заполнен. Измените окно, перенесите задачу или передайте её наёмному водителю.',
  },
  NO_ACTIVE_DRIVER: {
    title: 'Нет доступных водителей',
    detail: 'На выбранную дату нет активного штатного водителя со свободной сменой. Добавьте смену, измените дату или передайте задание наёмному водителю.',
  },
  NO_SHIFT_CAPACITY: {
    title: 'Нет доступных водителей',
    detail: 'Все штатные водители уже заняты в графике на выбранную дату. Измените время, перенесите задачу или передайте её наёмному водителю.',
  },
  NO_ACTIVE_VEHICLE: {
    title: 'Нет доступной машины',
    detail: 'На выбранную дату нет свободной активной машины. Измените график транспорта или выберите другую дату.',
  },
  CONTRACTOR_REQUIRED: {
    title: 'Нужен наёмный водитель',
    detail: 'Подходящий штатный ресурс не найден. Добавьте наёмного водителя на этот день и передайте ему доставку.',
  },
  CONTRACTOR_HANDOFF_REVIEW_REQUIRED: {
    title: 'Передача требует проверки',
    detail: 'Автоматические попытки остановлены. Проверьте передачу и повторите назначение при необходимости.',
  },
  NO_SUPPORT_RESOURCE: {
    title: 'Нет доступного ресурса',
    detail: 'Локальный и опорные склады не могут выполнить эту доставку в выбранный день. Измените дату или передайте задание наёмному водителю.',
  },
  OUTSIDE_ZONES: {
    title: 'Адрес недоступен для доставки',
    detail: 'Точка находится вне доступной территории склада или попадает под действующее дорожное ограничение. Проверьте адрес и выберите допустимый вариант доставки.',
  },
  TIME_WINDOW_CONFLICT: {
    title: 'Временное окно не подходит',
    detail: 'Водитель не успеет приехать в заданный интервал. Согласуйте другое время или дату.',
  },
  SHIFT_LIMIT_EXCEEDED: {
    title: 'Не хватает рабочего времени',
    detail: 'Новый рейс выходит за пределы смены водителя. Измените время, дату или назначьте другого водителя.',
  },
  CAPACITY_EXCEEDED: {
    title: 'Превышена вместимость',
    detail: 'Груз не помещается в выбранную машину на этом участке маршрута. Уменьшите груз или выберите другой транспорт.',
  },
  TIME_WINDOW_VIOLATION: {
    title: 'Нарушено временное окно',
    detail: 'После изменения водитель не успеет приехать в согласованный интервал. Измените окно или порядок заданий.',
  },
  SHIFT_EXCEEDED: {
    title: 'Рейс выходит за пределы смены',
    detail: 'После изменения водитель не успеет завершить маршрут в рабочее время. Измените время, дату или водителя.',
  },
  DRIVER_OVERLAP: {
    title: 'Водитель уже занят',
    detail: 'У водителя есть другой пересекающийся рейс. Выберите другое время или ресурс.',
  },
  VEHICLE_OVERLAP: {
    title: 'Машина уже занята',
    detail: 'У машины есть другой пересекающийся рейс. Выберите другое время или транспорт.',
  },
  TASK_ALREADY_ASSIGNED: {
    title: 'Задание уже назначено',
    detail: 'Обновите план: это задание уже находится в другом рейсе.',
  },
  ROUTE_NOT_RETURNED_TO_DEPOT: {
    title: 'Маршрут не завершён',
    detail: 'После изменения маршрут не возвращается на допустимый склад. Добавьте возврат на склад или отмените изменение.',
  },
  DETOUR_TOO_LARGE: {
    title: 'Слишком большой крюк',
    detail: 'Добавление точки делает маршрут слишком длинным. Выберите другой рейс или дату.',
  },
  HIGH_DETOUR: {
    title: 'Большой крюк',
    detail: 'Маршрут выполним, но дополнительный пробег заметно увеличился. Проверьте порядок точек перед публикацией.',
  },
  LOW_TIME_BUFFER: {
    title: 'Малый запас времени',
    detail: 'Маршрут выполним, но запас до следующего временного окна слишком мал. Увеличьте интервал или измените порядок точек.',
  },
  OVERTIME_WARNING: {
    title: 'Возможна переработка',
    detail: 'Маршрут заканчивается позже обычного рабочего времени водителя. Проверьте смену перед публикацией плана.',
  },
  REQUEST_NOT_READY: {
    title: 'Заявка не готова к планированию',
    detail: 'Подготовьте заявку к отгрузке и повторно запустите планирование.',
  },
  NO_ALLOWED_DATE: {
    title: 'Дата недоступна',
    detail: 'Выберите одну из разрешённых дат заявки или измените её допустимые даты.',
  },
  DUPLICATE_ASSIGNMENT_CONFLICT: {
    title: 'Заявка уже учтена',
    detail: 'Обновите план и проверьте существующее назначение этой заявки.',
  },
  NO_FEASIBLE_DELIVERY_PAIR: {
    title: 'Доставки нельзя объединить',
    detail: 'Разместите доставки в разных рейсах или измените их временные окна.',
  },
  NO_FEASIBLE_PICKUP_PAIR: {
    title: 'Вывозы нельзя объединить',
    detail: 'Разместите вывозы в разных рейсах или измените их временные окна.',
  },
  TRAILER_ACCESS_NOT_ALLOWED: {
    title: 'Проезд с прицепом запрещён',
    detail: 'Выберите машину без прицепа или согласуйте другой способ выполнения задания.',
  },
  CARGO_TOO_HEAVY: {
    title: 'Груз слишком тяжёлый',
    detail: 'Выберите транспорт с большей грузоподъёмностью или разделите груз.',
  },
  CARGO_TOO_LONG: {
    title: 'Груз слишком длинный',
    detail: 'Выберите транспорт с подходящей платформой или измените состав груза.',
  },
  CARGO_TOO_WIDE: {
    title: 'Груз слишком широкий',
    detail: 'Выберите транспорт с подходящей платформой или измените состав груза.',
  },
  CARGO_TOO_HIGH: {
    title: 'Груз слишком высокий',
    detail: 'Выберите подходящую конфигурацию транспорта и повторите планирование.',
  },
  TRAILER_REQUIRED: {
    title: 'Нужен прицеп',
    detail: 'Добавьте совместимый прицеп или уменьшите груз рейса.',
  },
  NO_COMPATIBLE_TRAILER: {
    title: 'Нет подходящего прицепа',
    detail: 'Выберите другую машину, добавьте совместимый прицеп или уменьшите груз.',
  },
  AXLE_LOAD_EXCEEDED: {
    title: 'Превышена нагрузка на ось',
    detail: 'Измените конфигурацию транспорта или уменьшите груз.',
  },
  NO_SAFE_ROUTE: {
    title: 'Безопасный маршрут не найден',
    detail: 'Проверьте параметры машины и груза либо выберите другой транспорт.',
  },
  ROUTING_PROVIDER_UNAVAILABLE: {
    title: 'Маршрутизация временно недоступна',
    detail: 'Повторите расчёт позже. Не подтверждайте рейс без проверенного грузового маршрута.',
  },
  ROUTING_PROFILE_INCOMPLETE: {
    title: 'Не заполнены параметры транспорта',
    detail: 'Заполните габариты и ограничения машины, прицепа и груза, затем повторите расчёт.',
  },
  UNKNOWN: {
    title: 'Невозможно назначить',
    detail: 'Не найдено допустимое сочетание даты, временного окна, водителя и транспорта. Проверьте эти условия и повторите планирование.',
  },
  OPTIMIZATION_TIME_LIMIT: {
    title: 'Лимит времени достигнут',
    detail: 'Проверка маршрутов не завершена за отведённое время. Повторите расчёт или увеличьте лимит планирования.',
  },
  NEGATIVE_LOAD: {
    title: 'Нарушена последовательность груза',
    detail: 'Проверьте порядок загрузки и выгрузки в рейсе.',
  },
  INVALID_TASK_QUANTITY: {
    title: 'Некорректное количество груза',
    detail: 'Укажите положительное количество бытовок и повторите действие.',
  },
  LOAD_DISCONTINUITY: {
    title: 'Нарушена последовательность загрузки',
    detail: 'Проверьте операции загрузки и выгрузки на каждом участке маршрута.',
  },
  INVALID_TIME: {
    title: 'Некорректное время рейса',
    detail: 'Проверьте порядок и время остановок, затем повторите действие.',
  },
  SOFT_WINDOW_RISK: {
    title: 'Есть риск опоздания',
    detail: 'Проверьте запас времени или назначьте более широкое окно.',
  },
  INEFFICIENT_EMPTY_RUN: {
    title: 'Большой пустой пробег',
    detail: 'Добавьте полезную загрузку или подтвердите причину пустого перегона.',
  },
  DELIVERY_WINDOW_MISSED: {
    title: 'Временное окно недоступно',
    detail: 'Выберите более позднее окно или измените состав маршрута.',
  },
  SHIFT_END_EXCEEDED: {
    title: 'Рейс не помещается в смену',
    detail: 'Измените время, дату или назначьте другого водителя.',
  },
  VEHICLE_CAPACITY_EXCEEDED: {
    title: 'Превышена вместимость',
    detail: 'Уменьшите груз или выберите машину и прицеп подходящей вместимости.',
  },
  NO_COMPATIBLE_VEHICLE: {
    title: 'Нет подходящей машины',
    detail: 'Выберите транспорт с подходящей вместимостью и конфигурацией.',
  },
  NO_FREE_DRIVER: {
    title: 'Нет доступных водителей',
    detail: 'Измените график, перенесите доставку или передайте её наёмному водителю.',
  },
  TRUCK_ROUTE_NOT_FOUND: {
    title: 'Грузовой маршрут не найден',
    detail: 'Проверьте адрес и параметры транспорта либо выберите другой транспорт.',
  },
  NO_FREE_TRIP_CAPACITY: {
    title: 'В рейсах нет свободного места',
    detail: 'Создайте отдельный рейс, уменьшите груз или выберите другую дату.',
  },
  WAREHOUSE_TURNAROUND_TOO_LONG: {
    title: 'Не хватает времени на складе',
    detail: 'Увеличьте интервал между рейсами или измените порядок заданий.',
  },
  NEXT_TRIP_AT_RISK: {
    title: 'Следующий рейс под угрозой',
    detail: 'Измените порядок заданий или увеличьте запас времени.',
  },
  LOCKED_STOP_CONFLICT: {
    title: 'Остановка зафиксирована',
    detail: 'Снимите фиксацию либо выберите другое место для задания.',
  },
  SLOT_ALREADY_HELD: {
    title: 'Слот уже удерживается',
    detail: 'Обновите доступность и выберите другой слот.',
  },
  PICKUP_DEFERRED: {
    title: 'Вывоз перенесён',
    detail: 'Проверьте предложенную дату и подтвердите перенос.',
  },
  INVALID_DAY_PLAN: {
    title: 'План дня содержит конфликт',
    detail: 'Исправьте отмеченные ограничения перед добавлением нового задания.',
  },
  VEHICLE_CAPACITY_ONE_CABIN: {
    title: 'Помещается только одна бытовка',
    detail: 'Уменьшите груз до одной бытовки или добавьте подходящий прицеп.',
  },
  SLOT_AFTER_RESOURCE_ARRIVAL: {
    title: 'Ресурс прибудет позже',
    detail: 'Выберите слот после расчётного прибытия и складских операций.',
  },
  CONTRACTOR_CONFIRMED: {
    title: 'Назначен наёмный водитель',
    detail: 'Проверьте состав переданного задания перед подтверждением.',
  },
  INVALID_TIME_WINDOW: {
    title: 'Некорректное временное окно',
    detail: 'Укажите окончание позже начала и повторите сохранение.',
  },
  INVALID_SHIFT_INTERVAL: {
    title: 'Некорректное время смены',
    detail: 'Укажите окончание смены позже её начала.',
  },
  INVALID_SHIFT_DATE_RANGE: {
    title: 'Некорректный период смены',
    detail: 'Выберите период от 1 до 31 дня.',
  },
  INVALID_SHIFT_MONTH: {
    title: 'Смена пересекает границу месяца',
    detail: 'Разделите период на две смены в пределах каждого месяца.',
  },
  DRIVER_SHIFT_OVERLAP: {
    title: 'Смены водителя пересекаются',
    detail: 'Измените даты или время одной из смен.',
  },
  VEHICLE_SHIFT_OVERLAP: {
    title: 'Машина уже занята в смене',
    detail: 'Измените время смены или выберите другую машину.',
  },
  CATALOG_VERSION_CONFLICT: {
    title: 'Данные уже изменились',
    detail: 'Обновите список смен, проверьте актуальные значения и повторите сохранение.',
  },
  DATABASE_CONSTRAINT_VIOLATION: {
    title: 'Объект связан с сохранённым планом',
    detail: 'Историческую смену нельзя удалить из уже сохранённого маршрута. При необходимости сделайте смену неактивной.',
  },
  SHIFT_WAREHOUSE_MISMATCH: {
    title: 'Смена относится к другому складу',
    detail: 'Выберите водителя и машину выбранного склада либо измените склад смены.',
  },
  RWMS_REQUEST_FAILED: {
    title: 'Не удалось обновить логистику',
    detail: 'Проверьте введённые данные и повторите попытку. Если ошибка сохранится, свяжитесь с администратором.',
  },
};

const GENERIC_DETAIL = 'Повторите действие. Если ошибка сохранится, свяжитесь с администратором.';
const TECHNICAL_DETAIL = /\b(?:HTTP(?:\/\d(?:\.\d)?)?|backend|exception|traceback|stack\s*trace|manual\s+change\s+invalid|rms\s+logistics\s+service|cycle|capacity|detour|shift\s+limits?|sql(?:alchemy)?|pydantic|validation\s+error|valhalla|nginx|uvicorn|fastapi)\b/iu;
const INTERNAL_CODE = /\b(?!RWMS\b)[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/u;
const ACTION_WORD = /(?:войдите|выберите|измените|обновите|проверьте|повторите|перенесите|добавьте|укажите|исправьте|дождитесь|обратитесь|согласуйте|освободите|свяжитесь|перезагрузите|включите|выключите|заполните|назначьте|создайте|разделите|уменьшите|увеличьте|подтвердите|снимите|подготовьте)/iu;

function collectProblemCodes(value: unknown, result: string[], depth = 0): void {
  if (depth > 4 || value === null || value === undefined) return;
  if (Array.isArray(value)) {
    value.forEach((item) => collectProblemCodes(item, result, depth + 1));
    return;
  }
  if (typeof value !== 'object') return;
  const record = value as Record<string, unknown>;
  for (const key of ['code', 'reason_code']) {
    const code = record[key];
    if (typeof code === 'string') result.push(code);
  }
  if (Array.isArray(record.reason_codes)) {
    record.reason_codes.forEach((code) => {
      if (typeof code === 'string') result.push(code);
    });
  }
  for (const key of ['errors', 'failures', 'detail']) {
    collectProblemCodes(record[key], result, depth + 1);
  }
}

function safeRussianDetail(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.replace(/\s+/gu, ' ').trim();
  if (
    !normalized
    || normalized.length > 500
    || !/[\u0400-\u04ff]/u.test(normalized)
    || TECHNICAL_DETAIL.test(normalized)
    || INTERNAL_CODE.test(normalized)
    || /(?:https?:\/\/|[{}[\]]|<\/?[a-z][^>]*>)/iu.test(normalized)
  ) return null;
  return normalized;
}

function statusAction(status: number | null): string {
  if (status === 0) return 'Проверьте соединение и повторите действие.';
  if (status === 401) return 'Войдите в RWMS и повторите действие.';
  if (status === 403) return 'Обратитесь к администратору за необходимыми правами.';
  if (status === 404) return 'Обновите страницу и выберите доступный объект.';
  if (status === 409) return 'Обновите данные и повторите действие.';
  if (status === 429) return 'Подождите немного и повторите действие.';
  if (status === 400 || status === 422) return 'Проверьте введённые данные и повторите действие.';
  return GENERIC_DETAIL;
}

function actionableDetail(detail: string, status: number | null): string {
  const punctuation = /[.!?]$/u.test(detail) ? detail : `${detail}.`;
  return ACTION_WORD.test(detail) ? punctuation : `${punctuation} ${statusAction(status)}`;
}

function diagnosticStrings(value: unknown, result: string[], depth = 0): void {
  if (depth > 4 || value === null || value === undefined) return;
  if (typeof value === 'string') {
    if (value.length <= 2_000) result.push(value);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item) => diagnosticStrings(item, result, depth + 1));
    return;
  }
  if (typeof value !== 'object') return;
  const record = value as Record<string, unknown>;
  for (const key of ['detail', 'title', 'message', 'message_ru', 'msg', 'errors', 'failures']) {
    diagnosticStrings(record[key], result, depth + 1);
  }
}

function inferredDomainMessage(error: ApiError): UserFacingError | null {
  const diagnostics: string[] = [];
  diagnosticStrings(error.problem, diagnostics);
  const text = diagnostics.join(' ').toLocaleLowerCase('en');
  if (!text) return null;
  if (/manual\s+change\s+invalid|edited\s+cycle|requested\s+task\s+move|no\s+feasible\s+cycle/u.test(text)) {
    return domainMessages.NO_FEASIBLE_CYCLE ?? null;
  }
  if (/driver.{0,30}overlap|overlap.{0,30}driver/u.test(text)) return domainMessages.DRIVER_OVERLAP ?? null;
  if (/vehicle.{0,30}overlap|overlap.{0,30}vehicle/u.test(text)) return domainMessages.VEHICLE_OVERLAP ?? null;
  if (/time\s+window|window.{0,30}violat/u.test(text)) return domainMessages.TIME_WINDOW_VIOLATION ?? null;
  if (/shift\s+(?:limit|exceed)|outside.{0,30}shift/u.test(text)) return domainMessages.SHIFT_EXCEEDED ?? null;
  if (/capacity|payload|overload/u.test(text)) return domainMessages.CAPACITY_EXCEEDED ?? null;
  if (/detour/u.test(text)) return domainMessages.DETOUR_TOO_LARGE ?? null;
  return null;
}

interface ResolvedUserFacingError {
  feedback: UserFacingError;
  specific: boolean;
}

function statusFeedback(status: number): UserFacingError {
  if (status === 0) return { title: 'Сервис недоступен', detail: statusAction(status) };
  if (status === 401) return { title: 'Требуется вход', detail: statusAction(status) };
  if (status === 403) return { title: 'Нет доступа', detail: statusAction(status) };
  if (status === 404) return { title: 'Данные не найдены', detail: statusAction(status) };
  if (status === 409) return { title: 'Данные уже изменились', detail: statusAction(status) };
  if (status === 429) return { title: 'Слишком много запросов', detail: statusAction(status) };
  if (status === 400 || status === 422) {
    return { title: 'Не удалось сохранить', detail: 'Один из параметров недопустим. Проверьте поля и повторите действие.' };
  }
  return { title: 'Операция не выполнена', detail: GENERIC_DETAIL };
}

function resolveUserFacingError(error: unknown): ResolvedUserFacingError {
  if (error instanceof ApiError) {
    const codes: string[] = [];
    collectProblemCodes(error.problem?.errors, codes);
    collectProblemCodes(error.problem?.failures, codes);
    collectProblemCodes(error.problem?.detail, codes);
    if (error.code) codes.push(error.code);
    const mapped = codes
      .map((code) => domainMessages[code])
      .find((value): value is UserFacingError => value !== undefined);
    if (mapped) return { feedback: mapped, specific: true };

    const inferred = inferredDomainMessage(error);
    if (inferred) return { feedback: inferred, specific: true };

    const safeDetail = safeRussianDetail(error.problem?.detail)
      ?? safeRussianDetail(error.problem?.title);
    if (safeDetail) {
      return {
        feedback: {
          title: error.status === 409 ? 'Данные уже изменились' : 'Операция не выполнена',
          detail: actionableDetail(safeDetail, error.status),
        },
        specific: true,
      };
    }
    return { feedback: statusFeedback(error.status), specific: false };
  }

  const safeDetail = error instanceof Error ? safeRussianDetail(error.message) : null;
  return {
    feedback: {
      title: 'Операция не выполнена',
      detail: safeDetail ? actionableDetail(safeDetail, null) : GENERIC_DETAIL,
    },
    specific: Boolean(safeDetail),
  };
}

/** Maps API/domain failures to Russian dispatcher guidance without exposing implementation text. */
export function userFacingError(error: unknown): UserFacingError {
  return resolveUserFacingError(error).feedback;
}

/** Returns a safe Russian detail for compact inline error surfaces. */
export function userFacingErrorDetail(error: unknown, fallback?: string): string {
  const resolved = resolveUserFacingError(error);
  const safeFallback = safeRussianDetail(fallback);
  if (!resolved.specific && safeFallback) {
    return actionableDetail(safeFallback, error instanceof ApiError ? error.status : null);
  }
  return resolved.feedback.detail;
}

/** Humanizes validation codes returned by the route planner. */
export function validationMessageRu(code: string, message?: string): string {
  const safeMessage = safeRussianDetail(message);
  return domainMessages[code]?.detail
    ?? (safeMessage ? actionableDetail(safeMessage, null) : null)
    ?? 'План нарушает одно из логистических ограничений. Измените время, состав рейса или водителя.';
}
