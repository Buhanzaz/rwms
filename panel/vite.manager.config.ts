import path from "node:path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

const appRoot = path.resolve(__dirname, "src/apps/manager")

export default defineConfig({
  root: appRoot,
  base: "/manager/",
  plugins: [react(), tailwindcss()],
  build: {
    outDir: path.resolve(__dirname, "dist-manager"),
    emptyOutDir: true,
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
})
