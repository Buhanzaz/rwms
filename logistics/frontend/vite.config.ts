import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const basePath = process.env.VITE_APP_BASE_PATH ?? '/';

export default defineConfig({
  base: basePath.endsWith('/') ? basePath : `${basePath}/`,
  plugins: [react(), tailwindcss()],
  build: { rollupOptions: { input: { main: 'index.html', yandex: 'yandex-map.html' } } },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://backend:8000',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './tests/setup.ts',
    exclude: ['playwright/**', 'node_modules/**', 'dist/**'],
    css: true,
    coverage: { reporter: ['text', 'html'] },
  },
});
