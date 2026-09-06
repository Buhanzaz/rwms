export interface ProblemDetails {
  type?: unknown;
  title?: unknown;
  status?: unknown;
  detail?: unknown;
  instance?: unknown;
  code?: unknown;
  errors?: unknown;
  failures?: unknown;
  request_id?: unknown;
  hold_id?: unknown;
  quarantine_count?: unknown;
}

const PUBLIC_ERROR_FORBIDDEN_TEXT = /\b(?:HTTP(?:\/\d(?:\.\d)?)?|backend|exception|traceback|stack\s*trace|sql(?:alchemy)?|pydantic|validation\s+error|rms\s+logistics\s+service|valhalla|nginx|uvicorn|fastapi)\b/iu;
const INTERNAL_ERROR_CODE = /\b(?!RWMS\b)[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/u;
const RUSSIAN_ACTION = /(?:войдите|выберите|измените|обновите|проверьте|повторите|перенесите|добавьте|укажите|исправьте|дождитесь|обратитесь|согласуйте|освободите|свяжитесь|перезагрузите|включите|выключите|заполните|назначьте|создайте|разделите|уменьшите|увеличьте|подтвердите|снимите)/iu;
const PROBLEM_CODE_MESSAGES: Readonly<Record<string, string>> = {
  DELIVERY_FORBIDDEN_ZONE: 'Адрес находится в зоне, где обслуживание запрещено. Измените адрес или границу исключения.',
  DELIVERY_OUTSIDE_ISOCHRONE: 'Адрес находится дальше предельной изохроны склада. Выберите другой склад или адрес.',
  POLICY_ZONE_VALUES_INVALID: 'Для особой цены укажите стоимость доставки и вывоза, а для ограничений удалите цены.',
};

function safeRussianProblemText(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.replace(/\s+/gu, ' ').trim();
  if (
    !normalized
    || normalized.length > 500
    || !/[\u0400-\u04ff]/u.test(normalized)
    || PUBLIC_ERROR_FORBIDDEN_TEXT.test(normalized)
    || INTERNAL_ERROR_CODE.test(normalized)
    || /(?:https?:\/\/|[{}[\]]|<\/?[a-z][^>]*>)/iu.test(normalized)
  ) return null;
  return normalized;
}

function statusAction(status: number): string {
  if (status === 0) return 'Проверьте соединение и повторите действие.';
  if (status === 401) return 'Войдите в RWMS и повторите действие.';
  if (status === 403) return 'Обратитесь к администратору за необходимыми правами.';
  if (status === 404) return 'Обновите страницу и выберите доступный объект.';
  if (status === 409) return 'Обновите данные и повторите действие.';
  if (status === 429) return 'Подождите немного и повторите действие.';
  if (status === 400 || status === 422) return 'Проверьте введённые данные и повторите действие.';
  return 'Повторите действие. Если ошибка сохранится, свяжитесь с администратором.';
}

function statusMessage(status: number): string {
  if (status === 0) return `Сервис логистики недоступен. ${statusAction(status)}`;
  if (status === 401) return `Сессия RWMS недоступна. ${statusAction(status)}`;
  if (status === 403) return `Недостаточно прав для этого действия. ${statusAction(status)}`;
  if (status === 404) return `Запрошенные данные не найдены. ${statusAction(status)}`;
  if (status === 409) return `Данные уже изменились. ${statusAction(status)}`;
  if (status === 429) return `Сервис получил слишком много запросов. ${statusAction(status)}`;
  if (status === 400 || status === 422) return `Запрос содержит недопустимые данные. ${statusAction(status)}`;
  return `Не удалось выполнить действие. ${statusAction(status)}`;
}

function actionableProblemText(value: string, status: number): string {
  const punctuation = /[.!?]$/u.test(value) ? value : `${value}.`;
  return RUSSIAN_ACTION.test(value) ? punctuation : `${punctuation} ${statusAction(status)}`;
}

function problemMessage(status: number, problem: ProblemDetails | null, fallback: string): string {
  // FastAPI validation arrays and all raw diagnostics stay in `problem`; they are never presentation text.
  const code = typeof problem?.code === 'string' ? problem.code : null;
  if (code && PROBLEM_CODE_MESSAGES[code]) return PROBLEM_CODE_MESSAGES[code];
  const candidate = safeRussianProblemText(problem?.detail)
    ?? safeRussianProblemText(problem?.title)
    ?? safeRussianProblemText(fallback);
  return candidate ? actionableProblemText(candidate, status) : statusMessage(status);
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly problem: ProblemDetails | null;

  constructor(status: number, problem: ProblemDetails | null, fallback: string) {
    const code = typeof problem?.code === 'string' ? problem.code : null;
    const localizedProblem = code && PROBLEM_CODE_MESSAGES[code]
      ? { ...(problem ?? {}), title: PROBLEM_CODE_MESSAGES[code] }
      : problem;
    super(problemMessage(status, localizedProblem, fallback));
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.problem = localizedProblem;
  }
}

/** Fetches an explicit gateway URL without retrying commands; abort remains caller-owned. */
export async function fetchApi(path: string, init: RequestInit = {}): Promise<Response> {
  try {
    return await fetch(path, init);
  } catch (error) {
    if (init.signal?.aborted || isAbort(error)) throw error;
    throw new ApiError(0, null, 'Сервис логистики недоступен. Проверьте соединение.');
  }
}

/** Decodes shared HTTP mechanics while each adapter validates its own domain payload. */
export async function readApiResponse<T = unknown>(
  response: Response,
  fallback: string,
  format: 'json' | 'auto' = 'json',
): Promise<T> {
  if (!response.ok) {
    let problem: ProblemDetails | null = null;
    try {
      const value: unknown = await response.json();
      if (value && typeof value === 'object' && !Array.isArray(value)) problem = value;
    } catch (error) {
      if (isAbort(error)) throw error;
    }
    throw new ApiError(response.status, problem, fallback);
  }
  if (response.status === 204) return undefined as T;
  try {
    if (format === 'auto' && !response.headers.get('content-type')?.includes('application/json')) {
      return await response.text() as T;
    }
    return await response.json() as T;
  } catch (error) {
    if (isAbort(error)) throw error;
    throw new ApiError(response.status, null, 'Сервис вернул некорректный ответ. Повторите действие.');
  }
}

function isAbort(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'name' in error && error.name === 'AbortError';
}
