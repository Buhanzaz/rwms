/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Public path embedded by Vite for deployments below a reverse-proxy prefix. */
  readonly VITE_APP_BASE_PATH?: string;
  /** Same-origin API prefix embedded by Vite for the current frontend build. */
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_MAP_STYLE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
