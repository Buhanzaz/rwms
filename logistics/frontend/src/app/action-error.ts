import { ApiError } from '../api/client';

export interface ActionErrorFeedback {
  tone: 'warning' | 'error';
  title: string;
  detail: string;
  refreshPlan: boolean;
}

const conflictTitles: Readonly<Record<string, string>> = {
  GENERATED_WORKLOAD_ALREADY_EXISTS: 'Такая нагрузка уже существует',
  GENERATED_REQUESTS_ALREADY_PLANNED: 'Перегенерация заблокирована сохранённым планом',
  CYCLE_LOCKED: 'Рейс заблокирован',
  TASK_LOCKED: 'Задача заблокирована',
};

/** Keeps domain conflicts distinct from optimistic plan-version conflicts. */
export function actionErrorFeedback(error: unknown): ActionErrorFeedback {
  if (error instanceof ApiError && error.code === 'PLAN_VERSION_CONFLICT') {
    return {
      tone: 'warning',
      title: 'План уже изменён',
      detail: 'Загружена актуальная версия; повторите действие.',
      refreshPlan: true,
    };
  }

  if (error instanceof ApiError && error.status === 409) {
    return {
      tone: 'warning',
      title: (error.code ? conflictTitles[error.code] : undefined) ?? 'Изменение конфликтует с текущими данными',
      detail: error.message,
      refreshPlan: false,
    };
  }

  return {
    tone: 'error',
    title: error instanceof ApiError && error.status === 0 ? 'Backend недоступен' : 'Операция не выполнена',
    detail: error instanceof Error ? error.message : 'Неизвестная ошибка',
    refreshPlan: false,
  };
}
