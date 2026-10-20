import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import { setSimulatorAccessTokenProvider } from '../src/api/client';

const jsdomWindow = (globalThis as typeof globalThis & { jsdom?: { window: Window } }).jsdom?.window;
if (jsdomWindow) {
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: jsdomWindow.localStorage });
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: jsdomWindow.sessionStorage });
}

class ResizeObserverStub implements ResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

Object.defineProperty(window, 'ResizeObserver', { writable: true, value: ResizeObserverStub });
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
});
Object.defineProperty(window.URL, 'createObjectURL', { writable: true, value: () => 'blob:maplibre-worker' });
Object.defineProperty(window.URL, 'revokeObjectURL', { writable: true, value: () => undefined });

beforeEach(() => {
  setSimulatorAccessTokenProvider(() => Promise.resolve('test-user-access-token'));
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
