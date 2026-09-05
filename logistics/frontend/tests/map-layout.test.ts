import { describe, expect, it } from 'vitest';
import { mapLayout } from '../src/app/map-layout';

describe('floating map layout', () => {
  it('reserves only the visible desktop panels', () => {
    const open = mapLayout(1600, 1000, 64, 420, true, false);
    const closed = mapLayout(1600, 1000, 64, 420, false, false);
    expect(open.padding).toEqual({ top: 148, bottom: 36, left: 284, right: 456 });
    expect(closed.padding.right).toBe(24);
    expect(open.style['--panel-top' as keyof typeof open.style]).toBe('88px');
  });

  it('keeps a map area above the mobile inspector and clear of navigation', () => {
    const { padding, style } = mapLayout(390, 844, 108, 420, true, false);
    expect(padding.left + padding.right).toBeLessThan(100);
    expect(padding.bottom).toBe(476);
    expect(padding.top).toBe(192);
    expect(844 - padding.top - padding.bottom).toBeGreaterThanOrEqual(80);
    expect(style['--map-controls-bottom' as keyof typeof style]).toBe('476px');
  });

  it('clamps a saved wide inspector after resizing the window', () => {
    const { panelWidth, style } = mapLayout(900, 800, 108, 800, true, false);
    expect(panelWidth).toBe(450);
    expect(style['--map-controls-right' as keyof typeof style]).toBe('474px');
  });

  it('reserves the measured simulation footer instead of assuming a one-row height', () => {
    const { style } = mapLayout(390, 844, 108, 420, true, true, 220);
    expect(style['--workspace-bottom' as keyof typeof style]).toBe('304px');
  });

  it.each([[320, 240], [760, 320], [1024, 400], [1920, 1080]])('retains a valid camera viewport at %i × %i during simulation', (width, height) => {
    const { padding } = mapLayout(width, height, 110, 700, true, true);
    expect(padding.left + padding.right).toBeLessThanOrEqual(width - 80);
    expect(padding.top + padding.bottom).toBeLessThanOrEqual(height - 80);
  });
});
