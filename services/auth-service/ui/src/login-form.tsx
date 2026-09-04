import { useEffect, useRef, useState, type FormEvent } from "react";

import { loadCsrfToken, type CsrfToken } from "@/auth/csrf";

const PREFILL_DEV_CREDENTIALS =
  import.meta.env.DEV ||
  import.meta.env.VITE_DEV_DEFAULT_CREDENTIALS === "true";
const AUTH_FORM_ANIMATION_DURATION_MS = 2100;
const REMEMBERED_LOGIN_KEY_PREFIX = "rwms.auth.remembered-login";

function readRememberedLogin(storageKey: string) {
  try {
    return window.localStorage.getItem(storageKey) ?? "";
  } catch {
    return "";
  }
}

function storeRememberedLogin(storageKey: string, login: string) {
  try {
    if (login === "") {
      window.localStorage.removeItem(storageKey);
    } else {
      window.localStorage.setItem(storageKey, login);
    }
  } catch {
    // The form must remain usable when browser storage is unavailable.
  }
}

export function LoginForm() {
  const search = new URLSearchParams(window.location.search);
  const isWorkerLogin = search.get("surface") === "worker";
  const hasLoginError = search.has("error");
  const hasLoggedOut = search.has("logout");
  const storageKey = `${REMEMBERED_LOGIN_KEY_PREFIX}.${isWorkerLogin ? "worker" : "panel"}`;
  const rememberedLogin = readRememberedLogin(storageKey);
  const initialLogin =
    rememberedLogin ||
    (PREFILL_DEV_CREDENTIALS && !isWorkerLogin ? "admin" : "");
  const [username, setUsername] = useState(initialLogin);
  const [password, setPassword] = useState(
    PREFILL_DEV_CREDENTIALS && !isWorkerLogin ? "admin" : "",
  );
  const [rememberLogin, setRememberLogin] = useState(rememberedLogin !== "");
  const [recoveryMessageVisible, setRecoveryMessageVisible] = useState(false);
  const [csrf, setCsrf] = useState<CsrfToken | null>(null);
  const [csrfLoaded, setCsrfLoaded] = useState(false);
  const [formReady, setFormReady] = useState(false);
  const usernameRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    document.title = isWorkerLogin ? "Вход — RWMS Рабочий" : "Вход — WMS Panel";

    let cancelled = false;

    void loadCsrfToken().then((token) => {
      if (!cancelled) {
        setCsrf(token);
        setCsrfLoaded(true);
      }
    });

    return () => {
      cancelled = true;
    };
  }, [isWorkerLogin]);

  useEffect(() => {
    const reducedMotion = window.matchMedia?.(
      "(prefers-reduced-motion: reduce)",
    ).matches;
    const delay = reducedMotion ? 0 : AUTH_FORM_ANIMATION_DURATION_MS;
    const timer = window.setTimeout(() => setFormReady(true), delay);
    return () => window.clearTimeout(timer);
  }, []);

  useEffect(() => {
    if (formReady) {
      usernameRef.current?.focus({ preventScroll: true });
    }
  }, [formReady]);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    if (csrf === null || username.trim() === "" || password === "") {
      event.preventDefault();
      return;
    }

    storeRememberedLogin(storageKey, rememberLogin ? username.trim() : "");
  }

  return (
    <section className="auth-layout" aria-labelledby="login-title">
      <div className="auth-brand auth-enter-logo">
        <img
          src="assets/block-box-logo.svg"
          className="auth-brand__logo"
          alt="Block Box"
        />
        <h1 id="login-title" className="sr-only">
          {isWorkerLogin ? "Вход для рабочего" : "Вход в систему"}
        </h1>
      </div>

      <form
        method="post"
        action="login"
        className="auth-form"
        aria-busy={!formReady || !csrfLoaded}
        onSubmit={handleSubmit}
      >
        {csrf ? (
          <input type="hidden" name={csrf.parameterName} value={csrf.token} />
        ) : null}

        <div className="auth-field">
          <label
            htmlFor="username"
            className="auth-field__label auth-enter auth-enter--label-left"
          >
            Логин
          </label>
          <input
            ref={usernameRef}
            id="username"
            name="username"
            type="text"
            className="auth-field__input auth-enter auth-enter--field-left"
            autoComplete="username"
            placeholder="Введите логин"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            aria-invalid={hasLoginError}
            aria-describedby={hasLoginError ? "login-error" : undefined}
            readOnly={!formReady}
            required
          />
        </div>

        <div className="auth-field">
          <label
            htmlFor="password"
            className="auth-field__label auth-enter auth-enter--label-right"
          >
            Пароль
          </label>
          <input
            id="password"
            name="password"
            type="password"
            className="auth-field__input auth-enter auth-enter--field-right"
            autoComplete="current-password"
            placeholder="Введите пароль"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            aria-invalid={hasLoginError}
            aria-describedby={hasLoginError ? "login-error" : undefined}
            readOnly={!formReady}
            required
          />
        </div>

        <div className="auth-options auth-enter auth-enter--options">
          <label className="auth-remember">
            <input
              type="checkbox"
              className="auth-remember__input"
              checked={rememberLogin}
              onChange={(event) => setRememberLogin(event.target.checked)}
              disabled={!formReady}
            />
            <span className="auth-remember__box" aria-hidden="true">
              <svg viewBox="0 0 18 18" focusable="false">
                <path d="M3.2 9.4 7.4 13.5 15 4.5" />
              </svg>
            </span>
            <span>Запомнить</span>
          </label>

          <button
            type="button"
            className="auth-text-action"
            disabled={!formReady}
            aria-describedby={
              recoveryMessageVisible ? "recovery-message" : undefined
            }
            onClick={() => setRecoveryMessageVisible(true)}
          >
            Забыли пароль?
          </button>
        </div>

        <div className="auth-messages" aria-live="polite">
          {hasLoginError ? (
            <p
              id="login-error"
              className="auth-message auth-message--error"
              role="alert"
            >
              Неверный логин или пароль.
            </p>
          ) : null}

          {hasLoggedOut ? (
            <p className="auth-message">Вы вышли из системы.</p>
          ) : null}

          {csrfLoaded && csrf === null ? (
            <p className="auth-message auth-message--error" role="alert">
              Не удалось подготовить защищённый вход. Обновите страницу.
            </p>
          ) : null}

          {recoveryMessageVisible ? (
            <p id="recovery-message" className="auth-message" role="status">
              Восстановление пароля пока недоступно.
            </p>
          ) : null}
        </div>

        <div className="auth-enter auth-enter--submit">
          <button
            type="submit"
            className="auth-submit"
            disabled={
              !formReady ||
              csrf === null ||
              username.trim() === "" ||
              password === ""
            }
          >
            <span>{csrfLoaded ? "Вход" : "Подготавливаем вход…"}</span>
          </button>
        </div>
      </form>
    </section>
  );
}
