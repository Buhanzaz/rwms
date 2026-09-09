import { describe, expect, it } from 'vitest';
import { ApiError } from '../src/api/client';
import { actionErrorFeedback } from '../src/app/action-error';
import { userFacingErrorDetail, validationMessageRu } from '../src/utils/user-facing-error';

describe('action error feedback', () => {
  it('refreshes the loaded plan only for an actual plan-version conflict', () => {
    expect(actionErrorFeedback(new ApiError(409, {
      code: 'PLAN_VERSION_CONFLICT',
      detail: 'The route plan changed after it was loaded',
    }, 'HTTP 409'))).toMatchObject({
      tone: 'warning',
      title: 'План уже изменён',
      refreshPlan: true,
    });
  });

  it('keeps a repeated generator run distinct from plan concurrency', () => {
    const feedback = actionErrorFeedback(new ApiError(409, {
      code: 'GENERATED_WORKLOAD_ALREADY_EXISTS',
      detail: 'Включите «Перегенерировать выбранный период».',
    }, 'HTTP 409'));

    expect(feedback).toEqual({
      tone: 'warning',
      title: 'Такая нагрузка уже существует',
      detail: 'Включите «Перегенерировать выбранный период».',
      refreshPlan: false,
    });
  });

  it('turns manual-plan diagnostics into actionable Russian guidance', () => {
    const feedback = actionErrorFeedback(new ApiError(422, {
      code: 'MANUAL_CHANGE_INVALID',
      detail: 'The edited cycle violates capacity, detour, window, or shift limits',
      errors: [{ code: 'NO_FEASIBLE_CYCLE' }],
    }, 'HTTP 422'));

    expect(feedback.title).toBe('Рейс нельзя добавить');
    expect(feedback.detail).toContain('График водителя');
    expect(`${feedback.title} ${feedback.detail}`).not.toMatch(/manual|cycle|capacity|HTTP|shift/iu);
  });

  it('does not present a workload database conflict as deletion of a historical shift', () => {
    const error = new ApiError(409, {
      code: 'DATABASE_CONSTRAINT_VIOLATION',
      detail: 'The requested change conflicts with existing logistics data',
      instance: '/api/warehouses/warehouse-1/generate-workload',
    }, 'HTTP 409');

    const feedback = actionErrorFeedback(error);

    expect(feedback).toEqual({
      tone: 'warning',
      title: 'Изменение не сохранено',
      detail: 'Изменение конфликтует с текущими данными. Обновите страницу и повторите действие. Если ошибка сохранится, свяжитесь с администратором.',
      refreshPlan: false,
    });
    expect(`${feedback.title} ${userFacingErrorDetail(error)}`).not.toMatch(/смен|маршрут|удал|HTTP|database/iu);
  });

  it('never exposes an upstream HTTP 400 from logistics settings', () => {
    const feedback = actionErrorFeedback(new ApiError(400, {
      code: 'RWMS_REQUEST_FAILED',
      detail: 'RMS Logistics Service returned HTTP 400',
    }, 'HTTP 400'));

    expect(feedback.title).toBe('Не удалось обновить логистику');
    expect(feedback.detail).toContain('Проверьте введённые данные');
    expect(`${feedback.title} ${feedback.detail}`).not.toMatch(/RMS|HTTP 400/iu);
  });

  it('explains the contractor fallback from a stable planner reason code', () => {
    expect(validationMessageRu('CONTRACTOR_REQUIRED')).toBe(
      'Подходящий штатный ресурс не найден. Добавьте наёмного водителя на этот день и передайте ему доставку.',
    );
  });

  it('filters mixed Russian and backend implementation details', () => {
    expect(userFacingErrorDetail(new Error('Backend вернул HTTP 400: validation error'))).toBe(
      'Повторите действие. Если ошибка сохранится, свяжитесь с администратором.',
    );
  });

  it('explains stopped contractor recovery without suggesting a plan-version refresh', () => {
    expect(actionErrorFeedback(new ApiError(409, {
      code: 'CONTRACTOR_HANDOFF_REVIEW_REQUIRED',
    }, 'HTTP 409'))).toEqual({
      tone: 'warning',
      title: 'Передача требует проверки',
      detail: 'Автоматические попытки остановлены. Проверьте передачу и повторите назначение при необходимости.',
      refreshPlan: false,
    });
  });

});
