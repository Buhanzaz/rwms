import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  base: "./",
  test: { environment: "jsdom" },
  plugins: [react(), tailwindcss()],
  build: {
    rollupOptions: {
      input: {
        main: path.resolve(__dirname, "index.html"),
        loginThrottled: path.resolve(__dirname, "login-throttled.html"),
      },
    },
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    proxy: {
      "/api": "http://localhost:9000",
      "/login": "http://localhost:9000",
      "/logout": "http://localhost:9000",
      "/oauth2": "http://localhost:9000",
    },
  },
});
