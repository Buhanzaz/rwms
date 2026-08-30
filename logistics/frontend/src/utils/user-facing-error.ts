import { ApiError } from '../api/client';

/** Stable operator-facing explanation with all transport diagnostics removed. */
export interface UserFacingError {
  title: string;
  detail: string;
}

const domainMessages: Readonly<Record<string, UserFacingError>> = {
  MANUAL_CHANGE_INVALID: {
    title: 'Изменение не помещается в план',
    detail: 'После изменения водитель не успеет выполнить рейсы в рабочее время и заданные окна.',
  },
  NO_FEASIBLE_CYCLE: {
    title: 'Рейс нельзя добавить',
    detail: 'График водителя на выбранное время уже заполнен. Измените окно, перенесите задачу или передайте её наёмному водителю.',
  },
  NO_ACTIVE_DRIVER: {
    title: 'Нет доступных водителей',
    detail: 'На выбранную дату нет активного штатного водителя со свободной сменой.',
  },
  NO_SHIFT_CAPACITY: {
    title: 'Нет доступных водителей',
    detail: 'Все штатные водители уже заняты в графике на выбранную дату.',
  },
  NO_ACTIVE_VEHICLE: {
    title: 'Нет доступной машины',
    detail: 'На выбранную дату нет свободной активной машины.',
  },
  CONTRACTOR_REQUIRED: {
    title: 'Нужен наёмный водитель',
    detail: 'Подходящий штатный ресурс не найден. Добавьте наёмного водителя на этот день и передайте ему доставку.',
  },
  NO_SUPPORT_RESOURCE: {
    title: 'Нет доступного ресурса',
    detail: 'Локальный и опорные склады не могут выполнить эту доставку в выбранный день. Измените дату или передайте задание наёмному водителю.',
  },
  OUTSIDE_ZONES: {
    title: 'Адрес недоступен для доставки',
    detail: 'Точка находится вне доступной территории склада или попадает под действующее дорожное ограничение.',
  },
  TIME_WINDOW_CONFLICT: {
    title: 'Временное окно не подходит',
    detail: 'Водитель не успеет приехать в заданный интервал. Согласуйте другое время или дату.',
  },
  SHIFT_LIMIT_EXCEEDED: {
    title: 'Не хватает рабочего времени',
    detail: 'Новый рейс выходит за пределы смены водителя.',
  },
  CAPACITY_EXCEEDED: {
    title: 'Превышена вместимость',
    detail: 'Груз не помещается в выбранную машину в этом участке маршрута.',
  },
  TIME_WINDOW_VIOLATION: {
    title: 'Нарушено временное окно',
    detail: 'После изменения водитель не успеет приехать в согласованный интервал.',
  },
  SHIFT_EXCEEDED: {
    title: 'Рейс выходит за пределы смены',
    detail: 'После изменения водитель не успеет завершить маршрут в рабочее время.',
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
    detail: 'После изменения маршрут не возвращается на допустимый склад.',
  },
  DETOUR_TOO_LARGE: {
    title: 'Слишком большой крюк',
    detail: 'Добавление точки делает маршрут слишком длинным. Выберите другой рейс или дату.',
  },
  HIGH_DETOUR: {
    title: 'Большой крюк',
    detail: 'Маршрут выполним, но дополнительный пробег заметно увеличился.',
  },
  LOW_TIME_BUFFER: {
    title: 'Малый запас времени',
    detail: 'Маршрут выполним, но запас до следующего временного окна слишком мал.',
  },
  OVERTIME_WARNING: {
    title: 'Возможна переработка',
    detail: 'Маршрут заканчивается позже обычного рабочего времени водителя.',
  },
  RWMS_REQUEST_FAILED: {
    title: 'Не удалось обновить логистику',
    detail: 'Проверьте введённые данные и повторите попытку. Если ошибка сохранится, свяжитесь с администратором.',
  },
};

const GENERIC_DETAIL = 'Повторите действие. Если ошибка сохранится, свяжитесь с администратором.';

function nestedReasonCode(error: ApiError): string | null {
  const errors = error.problem?.errors;
  if (!Array.isArray(errors)) return null;
  for (const value of errors) {
    if (!value || typeof value !== 'object') continue;
    const code = (value as Record<string, unknown>).code;
    if (typeof code === 'string') return code;
  }
  return null;
}

function safeRussianDetail(value: unknown): string | null {
  if (typeof value !== 'string' || !/[\u0400-\u04ff]/u.test(value)) return null;
  if (/\b(?:HTTP|Backend|Exception|Traceback|manual change invalid|RMS Logistics Service|cycle|capacity|detour|shift limits?|stack trace|SQLAlchemy|Pydantic|validation error)\b/iu.test(value)) return null;
  return value;
}

/** Maps API/domain failures to Russian dispatcher guidance without exposing implementation text. */
export function userFacingError(error: unknown): UserFacingError {
  if (error instanceof ApiError) {
    const reason = nestedReasonCode(error);
    const mapped = (reason ? domainMessages[reason] : undefined)
      ?? (error.code ? domainMessages[error.code] : undefined);
    if (mapped) return mapped;
    if (error.status === 0) {
      return { title: 'Сервис недоступен', detail: 'Проверьте соединение и повторите попытку.' };
    }
    if (error.status === 401) return { title: 'Требуется вход', detail: 'Войдите в RWMS и повторите действие.' };
    if (error.status === 403) return { title: 'Нет доступа', detail: 'Для этого действия нужны другие права.' };
    if (error.status === 404) return { title: 'Данные не найдены', detail: 'Объект уже удалён или недоступен на этом складе.' };
    const safeDetail = safeRussianDetail(error.message);
    if (safeDetail) return { title: error.status === 409 ? 'Данные уже изменились' : 'Операция не выполнена', detail: safeDetail };
    if (error.status === 400 || error.status === 422) {
      return { title: 'Не удалось сохранить', detail: 'Один из параметров недопустим. Проверьте поля и повторите попытку.' };
    }
  }
  const safeDetail = error instanceof Error ? safeRussianDetail(error.message) : null;
  return {
    title: 'Операция не выполнена',
    detail: safeDetail ?? GENERIC_DETAIL,
  };
}

/** Returns a safe Russian detail for compact inline error surfaces. */
export function userFacingErrorDetail(error: unknown, fallback?: string): string {
  const feedback = userFacingError(error);
  return fallback && feedback.detail === GENERIC_DETAIL ? fallback : feedback.detail;
}

/** Humanizes validation codes returned by the route planner. */
export function validationMessageRu(code: string, message?: string): string {
  return domainMessages[code]?.detail
    ?? safeRussianDetail(message)
    ?? 'План нарушает одно из логистических ограничений. Измените время, состав рейса или водителя.';
}
