import { ApiError } from '../api/client';
import { userFacingError } from '../utils/user-facing-error';

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
  DRIVER_SHIFT_OVERLAP: 'Смены водителя пересекаются',
  VEHICLE_SHIFT_OVERLAP: 'Машина уже занята в смене',
  CATALOG_VERSION_CONFLICT: 'Смена уже изменена',
  DATABASE_CONSTRAINT_VIOLATION: 'Объект используется в сохранённом плане',
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
      detail: userFacingError(error).detail,
      refreshPlan: false,
    };
  }

  const feedback = userFacingError(error);
  return {
    tone: 'error',
    title: feedback.title,
    detail: feedback.detail,
    refreshPlan: false,
  };
}
