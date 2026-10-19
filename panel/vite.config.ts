import path from "path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

import { isPanelAuthCallbackRequest } from "./src/lib/gateway-routes"

const gatewayDevTarget =
  process.env.RWMS_GATEWAY_DEV_TARGET ?? "http://localhost:8088"

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    allowedHosts: ["furiously-steadfast-crayfish.cloudpub.ru"],
    proxy: {
      "/api": {
        target: gatewayDevTarget,
        changeOrigin: false,
      },
      "/auth": {
        target: gatewayDevTarget,
        changeOrigin: false,
        bypass(request) {
          if (
            isPanelAuthCallbackRequest(
              request.url ?? "/",
              "http://localhost:8080"
            )
          ) {
            return "/index.html"
          }
        },
      },
    },
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
})
