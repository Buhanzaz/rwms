# UI входа auth-service

[English version](README.md)

Vite-приложение реализует страницу на основе `login-04`, которую использует
form login Spring Security. Сборка:

```text
npm ci
npm run build
```

Production artifact находится в `dist/`. Frontend build намеренно не копирует
его в каталоги Java sources.

## Контракт same-origin serving

Deployment auth-service обязан публиковать UI и Spring Security на одном
origin (`http://localhost:9000` при разработке):

- `GET /login` возвращает `dist/index.html`.
- `GET /assets/**` и `GET /wms-login-cover.png` возвращают файлы из `dist/`.
- `POST /login` без изменений перенаправляется в Spring Security.
- `/api/**`, `/oauth2/**`, `/.well-known/**`, `/connect/logout` и `/logout`
  без изменений перенаправляются в Spring Boot.

Разделение с учётом HTTP method обязательно: перенаправление `GET /login` в
Spring без view handler не показывает пользовательскую страницу, а публикация
`POST /login` как статического файла обходит authentication. Разделением может
владеть reverse proxy/static-resource layer, но он не должен менять request
body, cookies, query string или origin.

Форма получает CSRF token из `GET /api/auth/csrf`, отправляет token с именем
parameter, возвращённым этим endpoint, и передаёт стандартные поля `username`
и `password` в `/login`.

Development credentials заполняются заранее только в Vite development mode
или при явной сборке с `VITE_DEV_DEFAULT_CREDENTIALS=true`. Production builds
по умолчанию оставляют поля пустыми.
