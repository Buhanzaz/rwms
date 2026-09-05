import { afterEach, describe, expect, it, vi } from 'vitest';

afterEach(() => {
  window.localStorage.clear();
  vi.resetModules();
});

describe('logistics presentation persistence', () => {
  it('rehydrates the selected menu, mode, map tool, layers and shift filter', async () => {
    window.localStorage.setItem('rwms:logistics:presentation:v1', JSON.stringify({
      mode: 'SIMULATION',
      section: 'SHIFTS',
      mapTool: 'ADD_PICKUP',
      shiftVisibility: 'ARCHIVED',
      layers: { pickups: false, routes: false },
    }));
    vi.resetModules();

    const { useUiStore } = await import('../src/stores/ui-store');

    const state = useUiStore.getState();
    expect(state.mode).toBe('SIMULATION');
    expect(state.section).toBe('SHIFTS');
    expect(state.mapTool).toBe('ADD_PICKUP');
    expect(state.shiftVisibility).toBe('ARCHIVED');
    expect(state.layers).toMatchObject({ pickups: false, routes: false, deliveries: true });
  });

  it('writes presentation changes without storing operational entities', async () => {
    vi.resetModules();
    const { useUiStore } = await import('../src/stores/ui-store');

    useUiStore.getState().setSection('REQUESTS');
    useUiStore.getState().setMode('PLAN_DAY');
    useUiStore.getState().setShiftVisibility('ACTIVE');
    useUiStore.getState().toggleLayer('pickups');

    const stored: unknown = JSON.parse(window.localStorage.getItem('rwms:logistics:presentation:v1') ?? '{}');
    expect(stored).toMatchObject({
      mode: 'PLAN_DAY',
      section: 'REQUESTS',
      shiftVisibility: 'ACTIVE',
      layers: { pickups: false },
    });
    expect(stored).not.toHaveProperty('selected');
    expect(stored).not.toHaveProperty('notifications');
  });
});
