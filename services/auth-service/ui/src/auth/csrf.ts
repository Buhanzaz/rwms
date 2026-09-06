export type CsrfToken = {
  token: string;
  parameterName: string;
};

export async function loadCsrfToken(): Promise<CsrfToken | null> {
  try {
    const response = await fetch("api/auth/csrf", {
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    });

    if (!response.ok) {
      return null;
    }

    const body = (await response.json()) as {
      token?: unknown;
      parameterName?: unknown;
    };

    if (typeof body.token !== "string" || body.token.trim() === "") {
      return null;
    }

    return {
      token: body.token,
      parameterName:
        typeof body.parameterName === "string" ? body.parameterName : "_csrf",
    };
  } catch {
    return null;
  }
}
