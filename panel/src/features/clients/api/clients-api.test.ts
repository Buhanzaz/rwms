import { afterEach, describe, expect, it, vi } from "vitest"

import {
  createClient,
  getClient,
  listClients,
  parseRentalClient,
} from "@/features/clients/api/clients-api"

const CLIENT_ID = "11111111-1111-4111-8111-111111111111"
const MANAGER_ID = "22222222-2222-4222-8222-222222222222"
const clientResponse = {
  id: CLIENT_ID,
  version: 2,
  type: "LEGAL_ENTITY",
  displayName: "ООО Петров",
  phone: null,
  contactPerson: "Пётр Петров",
  email: "client@example.ru",
  responsibleManagerId: MANAGER_ID,
  responsibleManagerDisplayName: null,
  comment: "Постоянный клиент",
  source: "Рекомендация",
  additionalContacts: [{ name: "Анна Петрова", phone: "+79990000001" }],
  createdAt: "2026-08-09T08:00:00Z",
  updatedAt: "2026-08-09T09:00:00Z",
}

afterEach(() => vi.unstubAllGlobals())

describe("clients API", () => {
  it("parses a truthful nullable legacy phone and manager display name", () => {
    expect(parseRentalClient(clientResponse)).toMatchObject({
      id: CLIENT_ID,
      phone: null,
      responsibleManagerId: MANAGER_ID,
      responsibleManagerDisplayName: null,
    })
  })

  it("loads a server-paginated filtered client grid", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          content: [clientResponse],
          page: 2,
          size: 50,
          totalElements: 101,
          totalPages: 3,
        }),
        { headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      listClients({
        accessToken: "token",
        type: "LEGAL_ENTITY",
        search: " Петров ",
        page: 2,
        size: 50,
      })
    ).resolves.toMatchObject({ page: 2, totalElements: 101 })

    const [url] = fetchMock.mock.calls[0] as [string]
    const endpoint = new URL(url)
    expect(endpoint.pathname).toBe("/api/logistics/v1/clients")
    expect(Object.fromEntries(endpoint.searchParams)).toEqual({
      type: "LEGAL_ENTITY",
      search: "Петров",
      page: "2",
      size: "50",
    })
  })

  it("creates a client without browser-owned responsible-manager fields", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ ...clientResponse, phone: "+79990000000" }),
        {
          status: 201,
          headers: { "Content-Type": "application/json" },
        }
      )
    )
    vi.stubGlobal("fetch", fetchMock)
    const input = {
      clientType: "LEGAL_ENTITY" as const,
      displayName: "ООО Петров",
      phone: "+79990000000",
      contactPerson: "Пётр Петров",
      email: null,
      comment: null,
      source: "Рекомендация",
      additionalContacts: [{ name: "Анна Петрова", phone: "+7 999 000-00-01" }],
    }

    await createClient({
      accessToken: "token",
      idempotencyKey: "33333333-3333-4333-8333-333333333333",
      input,
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe("/api/logistics/v1/clients")
    expect(init.method).toBe("POST")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      "33333333-3333-4333-8333-333333333333"
    )
    expect(JSON.parse(String(init.body))).toEqual(input)
    expect(String(init.body)).not.toContain("responsibleManager")
  })

  it("loads one client through the public detail route", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(clientResponse), {
        headers: { "Content-Type": "application/json" },
      })
    )
    vi.stubGlobal("fetch", fetchMock)

    await getClient("token", CLIENT_ID)

    expect(new URL(fetchMock.mock.calls[0][0] as string).pathname).toBe(
      `/api/logistics/v1/clients/${CLIENT_ID}`
    )
  })

  it("fails closed on a malformed client response", () => {
    expect(() =>
      parseRentalClient({ ...clientResponse, id: "not-a-uuid" })
    ).toThrow("некорректный ответ")
    expect(() =>
      parseRentalClient({ ...clientResponse, type: "SOLE_PROPRIETOR" })
    ).toThrow("некорректный ответ")
  })
})
