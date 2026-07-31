import { afterEach } from "vitest"

const values = new Map<string, string>()
const localStorageMock: Storage = {
  get length() {
    return values.size
  },
  clear: () => values.clear(),
  getItem: (key) => values.get(key) ?? null,
  key: (index) => Array.from(values.keys())[index] ?? null,
  removeItem: (key) => values.delete(key),
  setItem: (key, value) => values.set(key, String(value)),
}

Object.defineProperty(window, "localStorage", {
  configurable: true,
  value: localStorageMock,
})

class TestResizeObserver implements ResizeObserver {
  observe(_target: Element, _options?: ResizeObserverOptions) {}

  unobserve(_target: Element) {}

  disconnect() {}
}

Object.defineProperty(globalThis, "ResizeObserver", {
  configurable: true,
  writable: true,
  value: TestResizeObserver,
})

afterEach(() => {
  window.localStorage.clear()
})
