import { describe, expect, it } from "vitest"

import { resolveHeaderBreadcrumbs } from "@/components/site-header-breadcrumbs"
import { canShowWarehouseHtmlImport } from "@/components/site-header-html-import-access"
import type { CurrentUser } from "@/features/auth/auth-model"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"

function warehouseUser(level: "VIEW" | "EDIT" | "MANAGE"): CurrentUser {
  return {
    id: "operator-1",
    username: "operator",
    displayName: "Оператор",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "WAREHOUSE_MANAGER",
    rentalAccess: false,
    warehouseAccessAll: false,
    warehouseAccesses: [{ warehouseId: WAREHOUSE_ID, level }],
  }
}

describe("resolveHeaderBreadcrumbs", () => {
  it("shows the HTML import action only on the exact warehouse route with EDIT access", () => {
    expect(
      canShowWarehouseHtmlImport(
        "/warehouse",
        warehouseUser("EDIT"),
        WAREHOUSE_ID
      )
    ).toBe(true)
    expect(
      canShowWarehouseHtmlImport(
        "/warehouse",
        warehouseUser("MANAGE"),
        WAREHOUSE_ID
      )
    ).toBe(true)
    expect(
      canShowWarehouseHtmlImport(
        "/warehouse",
        warehouseUser("VIEW"),
        WAREHOUSE_ID
      )
    ).toBe(false)
    expect(
      canShowWarehouseHtmlImport(
        "/warehouse/11111111-1111-4111-8111-111111111111",
        warehouseUser("EDIT"),
        WAREHOUSE_ID
      )
    ).toBe(false)
    expect(canShowWarehouseHtmlImport("/warehouse", null, null)).toBe(false)
  })

  it("keeps logistics screens at one breadcrumb level", () => {
    expect(
      resolveHeaderBreadcrumbs("/logistics/returns", "", null, null)
    ).toEqual([{ title: "Возврат из аренды" }])
    expect(
      resolveHeaderBreadcrumbs("/logistics/shipments", "", null, null)
    ).toEqual([{ title: "Отгрузка в аренду" }])
    expect(
      resolveHeaderBreadcrumbs("/logistics/transfers", "", null, null)
    ).toEqual([{ title: "Перемещения" }])
  })

  it("shows non-navigable settings context for settings sections", () => {
    expect(
      resolveHeaderBreadcrumbs("/settings/warehouses", "", null, null)
    ).toEqual([{ title: "Настройки" }, { title: "Склады" }])
    expect(resolveHeaderBreadcrumbs("/settings/users", "", null, null)).toEqual(
      [{ title: "Настройки" }, { title: "Пользователи" }]
    )
    expect(resolveHeaderBreadcrumbs("/settings/kpi", "", null, null)).toEqual([
      { title: "Настройки" },
      { title: "KPI" },
    ])
    expect(
      resolveHeaderBreadcrumbs("/settings/task-board", "", null, null)
    ).toEqual([{ title: "Настройки" }, { title: "Настройка доски задач" }])
  })

  it("adds the active estimate catalog section to the header", () => {
    expect(
      resolveHeaderBreadcrumbs(
        "/settings/estimates-repairs",
        "?catalog=repair-estimate-catalog-works",
        null,
        null
      )
    ).toEqual([
      { title: "Настройки" },
      { title: "Настройка смет и ремонтов" },
      { title: "Работы" },
    ])
  })

  it("keeps only meaningful write-off and inventory breadcrumbs navigable", () => {
    expect(resolveHeaderBreadcrumbs("/write-offs", "", null, null)).toEqual([
      { title: "Списание" },
      { title: "Склад" },
    ])
    expect(
      resolveHeaderBreadcrumbs("/write-offs/equipment", "", null, null)
    ).toEqual([{ title: "Списание" }, { title: "Доп. оборудование" }])
    expect(
      resolveHeaderBreadcrumbs("/write-offs", "?writeOffId=1", null, "Б-1")
    ).toEqual([
      { title: "Списание" },
      { title: "Склад", to: "/write-offs" },
      { title: "Б-1" },
    ])

    expect(
      resolveHeaderBreadcrumbs("/inventory/history/result-1", "", null, null)
    ).toEqual([
      { title: "Инвентаризация" },
      { title: "История", to: "/inventory/history" },
      { title: "Результат" },
    ])
    expect(
      resolveHeaderBreadcrumbs("/inventory/session-1/finish", "", null, null)
    ).toEqual([
      { title: "Инвентаризация" },
      { title: "Сессия", to: "/inventory/session-1" },
      { title: "Сверка" },
    ])
  })

  it("shows the booking number for an open booking", () => {
    expect(
      resolveHeaderBreadcrumbs(
        "/orders/33333333-3333-4333-8333-333333333333",
        "",
        null,
        null,
        "ORD-000001"
      )
    ).toEqual([
      { title: "Бронирование", to: "/orders" },
      { title: "ORD-000001" },
    ])
  })
})
