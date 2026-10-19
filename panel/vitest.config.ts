import { fileURLToPath, URL } from "node:url"
import { defineConfig } from "vitest/config"

export default defineConfig({
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    restoreMocks: true,
    // jsdom-heavy UI files otherwise contend for the event loop in the full 1,300+ test gate.
    maxWorkers: 2,
    testTimeout: 10_000,
  },
})
