import { fileURLToPath, URL } from "node:url"
import { configDefaults, defineConfig } from "vitest/config"

export default defineConfig({
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    restoreMocks: true,
    env: {
      VITE_DEV_MAINTENANCE_FIXTURES: "true",
    },
    exclude: [...configDefaults.exclude, "e2e/**"],
  },
})
