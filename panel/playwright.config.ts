import { defineConfig, devices } from "@playwright/test"

const baseURL = "http://127.0.0.1:4173"

export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  reporter: "list",
  use: {
    baseURL,
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "desktop",
      use: {
        ...devices["Desktop Chrome"],
        browserName: "chromium",
        channel: "chromium",
        viewport: { width: 1440, height: 1000 },
      },
    },
    {
      name: "tablet",
      use: {
        ...devices["Desktop Chrome"],
        browserName: "chromium",
        channel: "chromium",
        viewport: { width: 900, height: 1024 },
      },
    },
    {
      name: "mobile",
      use: {
        ...devices["iPhone 13"],
        browserName: "chromium",
        channel: "chromium",
      },
    },
  ],
  webServer: {
    command: "npm run dev -- --host 127.0.0.1 --port 4173 --strictPort",
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    env: {
      ...process.env,
      VITE_DEV_AUTH_BYPASS: "true",
      VITE_GATEWAY_URL: baseURL,
    },
  },
})
