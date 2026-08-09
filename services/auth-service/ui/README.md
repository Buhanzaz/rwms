# Auth service login UI

[Русская версия](README.ru.md)

The Vite application implements the `login-04`-based page used by Spring
Security form login. Build it with:

```text
npm ci
npm run build
```

The production artifact is `dist/`. It is deliberately not copied into Java
source directories by the frontend build.

## Same-origin serving contract

The auth-service deployment must expose the UI and Spring Security on the same
origin (`http://localhost:9000` in development):

- `GET /login` returns `dist/index.html`.
- `GET /assets/**` and `GET /wms-login-cover.png` return files from `dist/`.
- `POST /login` is forwarded to Spring Security unchanged.
- `/api/**`, `/oauth2/**`, `/.well-known/**`, `/connect/logout`, and `/logout`
  are forwarded to Spring Boot unchanged.

This method-aware split is required: forwarding `GET /login` to Spring without
a view handler produces no custom page, while serving `POST /login` as a static
file bypasses authentication. A reverse proxy/static-resource layer may own the
split; it must not change the request body, cookies, query string, or origin.

The form obtains the CSRF token from `GET /api/auth/csrf`, submits the token
using the parameter name returned by that endpoint, and posts the standard
`username` and `password` fields to `/login`.

Development credentials are prefilled only under Vite development mode or an
explicit `VITE_DEV_DEFAULT_CREDENTIALS=true` build. Production builds are empty
by default.
