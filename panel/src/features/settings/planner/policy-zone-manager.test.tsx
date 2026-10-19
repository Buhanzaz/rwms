import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import {
  afterAll,
  beforeAll,
  afterEach,
  describe,
  expect,
  it,
  vi,
} from "vitest"
import { planningApi as api } from "./planning-api"
import type { WarehousePolicyZone } from "./planning-types"
import PolicyZoneManager from "./policy-zone-manager"
import { planningFixture as warehouseFixture, SPB } from "./planning-fixtures"

const browserMethods = [
  "hasPointerCapture",
  "setPointerCapture",
  "releasePointerCapture",
  "scrollIntoView",
] as const
const descriptors = browserMethods.map(
  (name) =>
    [
      name,
      Object.getOwnPropertyDescriptor(HTMLElement.prototype, name),
    ] as const
)
beforeAll(() => {
  for (const name of browserMethods)
    Object.defineProperty(HTMLElement.prototype, name, {
      configurable: true,
      value: () => (name === "hasPointerCapture" ? false : undefined),
    })
})
afterAll(() => {
  for (const [name, descriptor] of descriptors) {
    if (descriptor)
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    else Reflect.deleteProperty(HTMLElement.prototype, name)
  }
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

const geometry = {
  type: "MultiPolygon" as const,
  coordinates: [
    [
      [
        [30.3, 59.9],
        [30.4, 59.9],
        [30.4, 60],
        [30.3, 59.9],
      ],
    ],
  ],
}

vi.mock("./policy-zone-map-editor", () => ({
  PolicyZoneMapEditor: ({
    onGeometryChange,
  }: {
    onGeometryChange: (value: typeof geometry) => void
  }) => (
    <button type="button" onClick={() => onGeometryChange(geometry)}>
      Нарисовать тестовый контур
    </button>
  ),
}))

function zoneFixture(
  overrides: Partial<WarehousePolicyZone> = {}
): WarehousePolicyZone {
  return {
    id: "22222222-2222-4222-8222-222222222222",
    warehouse_id: SPB,
    name: "Закрытый квартал",
    kind: "FORBIDDEN",
    color: "#EF4444",
    geometry,
    version: 1,
    delivery_price_rubles: null,
    pickup_price_rubles: null,
    created_at: "2026-08-31T10:00:00Z",
    updated_at: "2026-08-31T10:00:00Z",
    ...overrides,
  }
}

describe("settings policy-zone manager", () => {
  it("creates an exceptional restriction with exact geometry and a stable receipt", async () => {
    const user = userEvent.setup()
    const saved = zoneFixture()
    const list = vi
      .spyOn(api, "listPolicyZones")
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([saved])
    const create = vi.spyOn(api, "createPolicyZone").mockResolvedValue(saved)
    render(
      <PolicyZoneManager warehouse={warehouseFixture()} token="admin-token" />
    )

    expect(
      await screen.findByText("Обычная доставка остаётся изохронной")
    ).toBeTruthy()
    expect(
      screen.getByText(/Последняя изохрона ограничивает дальность/iu)
    ).toBeTruthy()
    await waitFor(() => expect(list).toHaveBeenCalledWith("admin-token", SPB))

    await user.type(screen.getByLabelText("Название"), "Закрытый квартал")
    screen.getByRole("combobox", { name: "Правило" }).focus()
    await user.keyboard("{ArrowDown}")
    await user.click(screen.getByRole("option", { name: "Запрещено" }))
    await user.click(
      screen.getByRole("button", { name: "Нарисовать тестовый контур" })
    )
    await user.click(screen.getByRole("button", { name: "Создать исключение" }))

    await waitFor(() => expect(create).toHaveBeenCalledOnce())
    expect(create.mock.calls[0]?.slice(0, 2)).toEqual(["admin-token", SPB])
    expect(create.mock.calls[0]?.[2]).toEqual({
      name: "Закрытый квартал",
      kind: "FORBIDDEN",
      color: "#EF4444",
      geometry,
      delivery_price_rubles: null,
      pickup_price_rubles: null,
    })
    expect(create.mock.calls[0]?.[3]).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu
    )
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
    expect(
      await screen.findByRole("button", { name: "Сохранить изменения" })
    ).toBeTruthy()
  })

  it("submits both prices and the exact optimistic fence for an existing special price", async () => {
    const user = userEvent.setup()
    const original = zoneFixture({
      id: "33333333-3333-4333-8333-333333333333",
      name: "Удалённый район",
      kind: "SPECIAL_PRICE",
      color: "#3B82F6",
      delivery_price_rubles: 20_000,
      pickup_price_rubles: 12_000,
      version: 7,
    })
    const updated = { ...original, version: 8, delivery_price_rubles: 21_000 }
    vi.spyOn(api, "listPolicyZones")
      .mockResolvedValueOnce([original])
      .mockResolvedValueOnce([updated])
    const update = vi.spyOn(api, "updatePolicyZone").mockResolvedValue(updated)
    render(
      <PolicyZoneManager warehouse={warehouseFixture()} token="admin-token" />
    )

    await user.click(
      await screen.findByRole("button", { name: /Удалённый район/iu })
    )
    await user.clear(screen.getByLabelText("Доставка, ₽"))
    await user.type(screen.getByLabelText("Доставка, ₽"), "21000")
    await user.click(
      screen.getByRole("button", { name: "Сохранить изменения" })
    )

    await waitFor(() => expect(update).toHaveBeenCalledOnce())
    expect(update).toHaveBeenCalledWith(
      "admin-token",
      SPB,
      original.id,
      expect.objectContaining({
        kind: "SPECIAL_PRICE",
        delivery_price_rubles: 21_000,
        pickup_price_rubles: 12_000,
        geometry,
      }),
      7
    )
    expect(
      await screen.findByRole("button", { name: "Сохранить изменения" })
    ).toBeTruthy()
  })
})
