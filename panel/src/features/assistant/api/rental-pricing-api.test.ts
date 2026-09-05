import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import {
  getCabinRentalPrices,
  assertPresentationRentalPrice,
  formatMonthlyRentalPrice,
  type CabinRentalPrices,
  getRentalPricingSettings,
  updateRentalPrice,
  validMonthlyRentalPrice,
  type RentalPricingSettings,
} from "./rental-pricing-api"

const id = (n: number) =>
  `00000000-0000-0000-0000-${String(n).padStart(12, "0")}`
const fetchMock = vi.fn()
function table(): RentalPricingSettings {
  return {
    version: 3,
    updatedAt: "2026-09-05T11:00:00Z",
    types: [
      {
        rentalTypeId: id(1),
        name: "БК-1",
        active: true,
        categories: [
          {
            categoryId: id(3),
            name: "Обычная",
            active: true,
            monthlyPriceRubles: "0",
          },
          {
            categoryId: id(4),
            name: "Новая",
            active: false,
            monthlyPriceRubles: "9223372036854775807",
          },
        ],
      },
    ],
  }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal("fetch", fetchMock)
})
afterEach(() => vi.unstubAllGlobals())

describe("rental pricing API", () => {
  it.each([
    { pricingVersion: null, monthlyPriceRubles: null },
    { pricingVersion: 0, monthlyPriceRubles: "0" },
    { pricingVersion: 5, monthlyPriceRubles: "9223372036854775807" },
  ])(
    "accepts exact presentation prices or explicit historical unknowns",
    (snapshot) => {
      expect(() => assertPresentationRentalPrice(snapshot)).not.toThrow()
    }
  )
  it.each([
    {},
    null,
    { pricingVersion: 0 },
    { pricingVersion: null, monthlyPriceRubles: "0" },
    { pricingVersion: 0, monthlyPriceRubles: null },
    { pricingVersion: -1, monthlyPriceRubles: "0" },
    { pricingVersion: 0, monthlyPriceRubles: 100 },
    { pricingVersion: 0, monthlyPriceRubles: "1e3" },
    { pricingVersion: 0, monthlyPriceRubles: "9223372036854775808" },
  ])("rejects incomplete or malformed frozen prices", (snapshot) => {
    expect(() => assertPresentationRentalPrice(snapshot)).toThrow()
  })
  it("loads exact amounts and inactive catalog rows through the authorized same-origin gateway", async () => {
    fetchMock.mockResolvedValue(Response.json(table()))
    const signal = new AbortController().signal
    await expect(getRentalPricingSettings("token", signal)).resolves.toEqual(
      table()
    )
    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const url = new URL(input)
    expect(url.origin).toBe(window.location.origin)
    expect(url.pathname).toBe("/api/logistics/v1/settings/rental-prices")
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer token")
    expect(init.signal).toBe(signal)
  })
  it("puts one existing pair with its exact price and expected version", async () => {
    fetchMock.mockResolvedValue(Response.json(table()))
    await updateRentalPrice({
      accessToken: "token",
      rentalTypeId: id(1),
      categoryId: id(4),
      expectedVersion: 2,
      monthlyPriceRubles: "9223372036854775807",
    })
    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(input).pathname).toBe(
      `/api/logistics/v1/settings/rental-prices/${id(1)}/${id(4)}`
    )
    expect(init.method).toBe("PUT")
    expect(JSON.parse(init.body as string)).toEqual({
      expectedVersion: 2,
      monthlyPriceRubles: "9223372036854775807",
    })
  })
  it.each([
    "",
    "-1",
    "1.50",
    "1,50",
    "1e3",
    " 100",
    "01",
    "9223372036854775808",
  ])("rejects invalid input %s before transport", async (price) => {
    expect(validMonthlyRentalPrice(price)).toBe(false)
    await expect(
      updateRentalPrice({
        accessToken: "token",
        rentalTypeId: id(1),
        categoryId: id(3),
        expectedVersion: 3,
        monthlyPriceRubles: price,
      })
    ).rejects.toThrow()
    expect(fetchMock).not.toHaveBeenCalled()
  })
  it.each([
    (data: RentalPricingSettings) => ({
      ...data,
      version: Number.MAX_SAFE_INTEGER + 1,
    }),
    (data: RentalPricingSettings) => ({ ...data, updatedAt: "yesterday" }),
    (data: RentalPricingSettings) => ({
      ...data,
      types: [data.types[0], data.types[0]],
    }),
    (data: RentalPricingSettings) => ({
      ...data,
      types: [{ ...data.types[0], rentalTypeId: "bad" }],
    }),
    (data: RentalPricingSettings) => ({
      ...data,
      types: [{ ...data.types[0], active: null }],
    }),
    (data: RentalPricingSettings) => ({
      ...data,
      types: [
        {
          ...data.types[0],
          categories: [
            data.types[0].categories[0],
            data.types[0].categories[0],
          ],
        },
      ],
    }),
    (data: RentalPricingSettings) => ({
      ...data,
      types: [
        {
          ...data.types[0],
          categories: [
            { ...data.types[0].categories[0], monthlyPriceRubles: 100 },
          ],
        },
      ],
    }),
    (data: RentalPricingSettings) => ({
      ...data,
      types: [
        {
          ...data.types[0],
          categories: [
            {
              ...data.types[0].categories[0],
              monthlyPriceRubles: "9223372036854775808",
            },
          ],
        },
      ],
    }),
    (data: RentalPricingSettings) => ({
      ...data,
      types: [
        data.types[0],
        { ...data.types[0], rentalTypeId: id(2), categories: [] },
      ],
    }),
    (data: RentalPricingSettings) => ({
      ...data,
      types: [
        data.types[0],
        {
          ...data.types[0],
          rentalTypeId: id(2),
          categories: data.types[0].categories.map((row) => ({
            ...row,
            name: "Другое имя",
          })),
        },
      ],
    }),
  ])(
    "rejects malformed or incomplete taxonomy instead of inventing zero prices",
    async (change) => {
      fetchMock.mockResolvedValue(Response.json(change(table())))
      await expect(getRentalPricingSettings("token")).rejects.toMatchObject({
        code: "INVALID_API_RESPONSE",
      })
    }
  )
  it("preserves conflicts and dependency errors", async () => {
    fetchMock.mockResolvedValueOnce(
      Response.json(
        { code: "RENTAL_PRICING_VERSION_CONFLICT" },
        { status: 409 }
      )
    )
    await expect(
      updateRentalPrice({
        accessToken: "token",
        rentalTypeId: id(1),
        categoryId: id(3),
        expectedVersion: 2,
        monthlyPriceRubles: "0",
      })
    ).rejects.toMatchObject({
      status: 409,
      code: "RENTAL_PRICING_VERSION_CONFLICT",
    })
    fetchMock.mockResolvedValueOnce(
      Response.json({ code: "RENTAL_PRICING_UNAVAILABLE" }, { status: 503 })
    )
    await expect(getRentalPricingSettings("token")).rejects.toMatchObject({
      status: 503,
    })
  })
  it("never requests prices without a token", async () => {
    await expect(getRentalPricingSettings(" ")).rejects.toThrow(
      "Не получен токен доступа"
    )
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

function cabinPrices(): CabinRentalPrices {
  return {
    warehouseId: id(8),
    pricingVersion: 3,
    cabins: [
      {
        rentalItemId: id(9),
        rentalItemVersion: 2,
        rentalTypeId: id(1),
        categoryId: id(3),
        monthlyPriceRubles: "0",
      },
      {
        rentalItemId: id(10),
        rentalItemVersion: 8,
        rentalTypeId: id(1),
        categoryId: id(4),
        monthlyPriceRubles: "9223372036854775807",
      },
    ],
  }
}

describe("cabin price lookup", () => {
  it("requests only the specified warehouse and cabin IDs and preserves exact amounts", async () => {
    fetchMock.mockResolvedValue(Response.json(cabinPrices()))
    const signal = new AbortController().signal
    await expect(
      getCabinRentalPrices("token", id(8), [id(9), id(10)], signal)
    ).resolves.toEqual(cabinPrices())
    const [input, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(input).origin).toBe(window.location.origin)
    expect(new URL(input).pathname).toBe(
      "/api/logistics/v1/cabins/rental-prices"
    )
    expect(init.method).toBe("POST")
    expect(init.signal).toBe(signal)
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer token")
    expect(JSON.parse(init.body as string)).toEqual({
      warehouseId: id(8),
      rentalItemIds: [id(9), id(10)],
    })
    expect(
      formatMonthlyRentalPrice("9223372036854775807").replace(/\s/g, "")
    ).toBe("9223372036854775807₽/мес.")
    expect(formatMonthlyRentalPrice("0")).toBe("0 ₽/мес.")
  })
  it.each([
    (data: CabinRentalPrices) => ({ ...data, warehouseId: id(11) }),
    (data: CabinRentalPrices) => ({ ...data, pricingVersion: -1 }),
    (data: CabinRentalPrices) => ({ ...data, cabins: [data.cabins[0]] }),
    (data: CabinRentalPrices) => ({
      ...data,
      cabins: [data.cabins[0], data.cabins[0]],
    }),
    (data: CabinRentalPrices) => ({
      ...data,
      cabins: [data.cabins[0], { ...data.cabins[1], rentalItemId: id(11) }],
    }),
    (data: CabinRentalPrices) => ({
      ...data,
      cabins: data.cabins.map((row) => ({ ...row, categoryId: null })),
    }),
    (data: CabinRentalPrices) => ({
      ...data,
      cabins: data.cabins.map((row) => ({ ...row, rentalItemVersion: -1 })),
    }),
    (data: CabinRentalPrices) => ({
      ...data,
      cabins: data.cabins.map((row) => ({ ...row, monthlyPriceRubles: 0 })),
    }),
    (data: CabinRentalPrices) => ({
      ...data,
      cabins: data.cabins.map((row) => ({
        ...row,
        monthlyPriceRubles: "9223372036854775808",
      })),
    }),
  ])("rejects mismatched, incomplete or malformed prices", async (change) => {
    fetchMock.mockResolvedValue(Response.json(change(cabinPrices())))
    await expect(
      getCabinRentalPrices("token", id(8), [id(9), id(10)])
    ).rejects.toMatchObject({ code: "INVALID_API_RESPONSE" })
  })
  it.each([
    [],
    [id(9), id(9)],
    Array.from({ length: 101 }, (_, index) => id(index + 100)),
  ])("rejects an invalid batch before transport", async (...ids) => {
    await expect(getCabinRentalPrices("token", id(8), ids)).rejects.toThrow()
    expect(fetchMock).not.toHaveBeenCalled()
  })
  it("does not fabricate free rent when the price owner is unavailable", async () => {
    fetchMock.mockResolvedValue(
      Response.json({ code: "RENTAL_PRICING_UNAVAILABLE" }, { status: 503 })
    )
    await expect(
      getCabinRentalPrices("token", id(8), [id(9)])
    ).rejects.toMatchObject({ status: 503 })
  })
})
