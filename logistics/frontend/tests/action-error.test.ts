import { describe, expect, it } from 'vitest';
import { ApiError } from '../src/api/client';
import { actionErrorFeedback } from '../src/app/action-error';

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

});
