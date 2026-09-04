import { describe, expect, it } from 'vitest';
import { ApiError } from '../src/api/client';
import {
  userFacingError,
  userFacingErrorDetail,
  validationMessageRu,
} from '../src/utils/user-facing-error';

describe('user-facing logistics errors', () => {
  it('uses a specific nested planner reason instead of the raw manual-change diagnostic', () => {
    const error = new ApiError(422, {
      code: 'MANUAL_CHANGE_INVALID',
      detail: 'The edited cycle violates capacity, detour, window, or shift limits',
      errors: [{
        code: 'CAPACITY_EXCEEDED',
        message: 'Route load is above vehicle capacity',
        cycle_id: 'cycle-internal-1',
      }],
    }, 'HTTP 422');

    const feedback = userFacingError(error);

    expect(feedback.title).toBe('Превышена вместимость');
    expect(feedback.detail).toContain('выберите другой транспорт');
    expect(`${feedback.title} ${feedback.detail}`).not.toMatch(/manual|cycle|capacity|detour|HTTP|internal/iu);
  });

  it('recognizes a legacy planner message without exposing its English text', () => {
    const feedback = userFacingError(new ApiError(422, {
      detail: 'The edited cycle violates capacity, detour, window, or shift limits',
    }, 'HTTP 422'));

    expect(feedback.title).toBe('Рейс нельзя добавить');
    expect(feedback.detail).toContain('Измените окно');
    expect(`${feedback.title} ${feedback.detail}`).not.toMatch(/edited|cycle|capacity|detour|shift/iu);
  });

  it('never renders a raw validation array or internal service response', () => {
    const feedback = userFacingError(new ApiError(422, {
      title: 'RequestValidationError',
      detail: [{
        loc: ['body', 'window_start'],
        msg: 'Input should be a valid time',
        type: 'time_parsing',
      }],
      failures: [{ service: 'Valhalla', detail: '{"trace":"abc"}' }],
    }, 'RMS Logistics Service returned HTTP 422'));

    expect(feedback).toEqual({
      title: 'Не удалось сохранить',
      detail: 'Один из параметров недопустим. Проверьте поля и повторите действие.',
    });
    expect(`${feedback.title} ${feedback.detail}`).not.toMatch(/RequestValidationError|window_start|time_parsing|Valhalla|HTTP|trace|\{/iu);
  });

  it('preserves a safe Russian domain detail and adds the next action', () => {
    const feedback = userFacingError(new ApiError(404, {
      detail: 'Выбранный склад отсутствует в справочнике RWMS',
      code: 'RWMS_WAREHOUSE_NOT_FOUND',
    }, 'HTTP 404'));

    expect(feedback.detail).toBe(
      'Выбранный склад отсутствует в справочнике RWMS. Обновите страницу и выберите доступный объект.',
    );
  });

  it('uses a safe call-site fallback for an unknown technical failure', () => {
    const detail = userFacingErrorDetail(
      new Error('{"trace":"internal","service":"backend"}'),
      'Не удалось загрузить изохроны',
    );

    expect(detail).toBe(
      'Не удалось загрузить изохроны. Повторите действие. Если ошибка сохранится, свяжитесь с администратором.',
    );
  });

  it('maps window, capacity and shift reason codes to distinct guidance', () => {
    expect(validationMessageRu('TIME_WINDOW_VIOLATION')).toContain('Измените окно');
    expect(validationMessageRu('VEHICLE_CAPACITY_EXCEEDED')).toContain('Уменьшите груз');
    expect(validationMessageRu('SHIFT_END_EXCEEDED')).toContain('назначьте другого водителя');
  });

  it('does not hide an unknown assignment failure behind a generic route message', () => {
    const feedback = userFacingError(new ApiError(422, { code: 'UNKNOWN' }, 'HTTP 422'));

    expect(feedback.title).toBe('Невозможно назначить');
    expect(feedback.detail).toContain('даты, временного окна, водителя и транспорта');
  });
});
