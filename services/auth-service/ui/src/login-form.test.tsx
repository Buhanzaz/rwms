import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { LoginForm } from "./login-form";

beforeEach(() => {
  vi.useFakeTimers();
  window.history.replaceState(null, "", "/auth/login-ui?surface=worker");
  window.localStorage.clear();
  vi.stubGlobal("matchMedia", () => ({ matches: true }));
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ token: "token", parameterName: "server_csrf" }),
        ),
      ),
  );
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

async function ready() {
  await act(async () => {
    vi.advanceTimersByTime(0);
  });
}

function fillCredentials() {
  fireEvent.change(screen.getByLabelText("Логин"), {
    target: { value: " worker " },
  });
  fireEvent.change(screen.getByLabelText("Пароль"), {
    target: { value: "password" },
  });
}

function form() {
  return screen.getByLabelText("Логин").closest("form")!;
}

it("blocks native submit until CSRF arrives, then sends the exact hidden parameter", async () => {
  let resolve!: (response: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn().mockReturnValue(
      new Promise<Response>((done) => {
        resolve = done;
      }),
    ),
  );
  render(<LoginForm />);
  await ready();
  fillCredentials();
  expect(
    screen.getByRole("button", { name: "Подготавливаем вход…" }),
  ).toHaveProperty("disabled", true);
  expect(fireEvent.submit(form())).toBe(false);
  expect(form().querySelector('input[type="hidden"]')).toBeNull();

  await act(async () => {
    resolve(
      new Response(
        JSON.stringify({
          token: "server-token",
          parameterName: "csrf_dynamic",
        }),
      ),
    );
  });
  expect(screen.getByRole("button", { name: "Вход" })).toHaveProperty(
    "disabled",
    false,
  );
  expect(form().getAttribute("action")).toBe("login");
  expect(form().getAttribute("method")).toBe("post");
  expect(new FormData(form()).get("csrf_dynamic")).toBe("server-token");
  expect(fireEvent.submit(form())).toBe(true);
});

it.each(["http", "invalid token"])(
  "shows a preparation error and blocks submission after %s",
  async (failure) => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify({ token: "" }), {
            status: failure === "http" ? 503 : 200,
          }),
        ),
    );
    render(<LoginForm />);
    await ready();
    fillCredentials();
    expect(screen.getByRole("alert").textContent).toContain(
      "Не удалось подготовить защищённый вход",
    );
    expect(screen.getByRole("button", { name: "Вход" })).toHaveProperty(
      "disabled",
      true,
    );
    expect(fireEvent.submit(form())).toBe(false);
  },
);

it("keeps submit disabled during the entrance animation and for missing credentials", async () => {
  vi.stubGlobal("matchMedia", () => ({ matches: false }));
  render(<LoginForm />);
  await ready();
  expect(screen.getByLabelText("Логин")).toHaveProperty("readOnly", true);
  expect(screen.getByRole("button", { name: "Вход" })).toHaveProperty(
    "disabled",
    true,
  );
  await act(async () => {
    vi.advanceTimersByTime(2100);
  });
  expect(screen.getByLabelText("Логин")).toHaveProperty("readOnly", false);
  expect(screen.getByRole("button", { name: "Вход" })).toHaveProperty(
    "disabled",
    true,
  );
  expect(fireEvent.submit(form())).toBe(false);
  fillCredentials();
  fireEvent.change(screen.getByLabelText("Логин"), { target: { value: "  " } });
  expect(fireEvent.submit(form())).toBe(false);
  expect(screen.getByRole("button", { name: "Вход" })).toHaveProperty(
    "disabled",
    true,
  );
});

it.each(["panel", "worker"])(
  "stores and clears remembered %s login independently",
  async (surface) => {
    const other = surface === "panel" ? "worker" : "panel";
    window.history.replaceState(null, "", `/auth/login-ui?surface=${surface}`);
    window.localStorage.setItem(
      `rwms.auth.remembered-login.${surface}`,
      "remembered",
    );
    window.localStorage.setItem(
      `rwms.auth.remembered-login.${other}`,
      "other-login",
    );
    render(<LoginForm />);
    await ready();
    expect(screen.getByLabelText("Логин")).toHaveProperty(
      "value",
      "remembered",
    );
    expect(screen.getByRole("checkbox", { name: "Запомнить" })).toHaveProperty(
      "checked",
      true,
    );
    fillCredentials();
    expect(fireEvent.submit(form())).toBe(true);
    expect(
      window.localStorage.getItem(`rwms.auth.remembered-login.${surface}`),
    ).toBe("worker");
    expect(
      window.localStorage.getItem(`rwms.auth.remembered-login.${other}`),
    ).toBe("other-login");
    fireEvent.click(screen.getByRole("checkbox", { name: "Запомнить" }));
    expect(fireEvent.submit(form())).toBe(true);
    expect(
      window.localStorage.getItem(`rwms.auth.remembered-login.${surface}`),
    ).toBeNull();
    expect(
      window.localStorage.getItem(`rwms.auth.remembered-login.${other}`),
    ).toBe("other-login");
    expect(
      [...Array(window.localStorage.length)].map((_, index) =>
        window.localStorage.key(index),
      ),
    ).not.toContain("password");
  },
);
