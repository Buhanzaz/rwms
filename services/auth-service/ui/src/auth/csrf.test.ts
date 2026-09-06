import { afterEach, expect, it, vi } from "vitest";
import { loadCsrfToken } from "./csrf";

afterEach(() => vi.unstubAllGlobals());

it("loads the same-origin CSRF endpoint and preserves the server parameter", async () => {
  const fetch = vi
    .fn()
    .mockResolvedValue(
      new Response(
        JSON.stringify({ token: "opaque-token", parameterName: "csrf_value" }),
      ),
    );
  vi.stubGlobal("fetch", fetch);
  await expect(loadCsrfToken()).resolves.toEqual({
    token: "opaque-token",
    parameterName: "csrf_value",
  });
  expect(fetch).toHaveBeenCalledWith("api/auth/csrf", {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
});

it("uses the documented default parameter when omitted", async () => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ token: "opaque-token" })),
      ),
  );
  await expect(loadCsrfToken()).resolves.toEqual({
    token: "opaque-token",
    parameterName: "_csrf",
  });
});

it.each([
  null,
  {},
  { token: null },
  { token: 123 },
  { token: "" },
  { token: "   " },
])("refuses a malformed token response: %j", async (body) => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response(JSON.stringify(body))),
  );
  await expect(loadCsrfToken()).resolves.toBeNull();
});

it.each(["http", "json", "network"])(
  "refuses a %s failure",
  async (failure) => {
    const fetch = vi.fn();
    if (failure === "network")
      fetch.mockRejectedValue(new TypeError("offline"));
    else
      fetch.mockResolvedValue(
        new Response("<html>failure</html>", {
          status: failure === "http" ? 503 : 200,
        }),
      );
    vi.stubGlobal("fetch", fetch);
    await expect(loadCsrfToken()).resolves.toBeNull();
  },
);
