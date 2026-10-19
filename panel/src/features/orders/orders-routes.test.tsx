import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import type {
  CurrentUser,
  UserGlobalRole,
  WarehouseAccessLevel,
} from "@/features/auth/auth-model"

const authRuntime = vi.hoisted(() => ({
  accessToken: "orders-token" as string | null,
  currentUser: null as CurrentUser | null,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => authRuntime,
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    warehouses: [
      {
        id: "22222222-2222-4222-8222-222222222222",
        version: 3,
        name: "Москва",
        city: "Москва",
        address: "Складская, 1",
        timeZone: "Europe/Moscow",
        active: true,
        sortOrder: 1,
      },
    ],
  }),
}))

import { useOrdersModule } from "@/features/orders/orders-module-context"
import { VaultPanelOrdersModuleAdapter } from "@/features/orders/orders-routes"

afterEach(cleanup)
beforeEach(() => {
  authRuntime.accessToken = "orders-token"
  authRuntime.currentUser = {
    id: "11111111-1111-4111-8111-111111111111",
    username: "manager",
    displayName: "",
    firstName: null,
    lastName: null,
    email: null,
    principalType: "USER",
    globalRole: "WMS_ADMIN",
    rentalAccess: true,
    warehouseAccessAll: false,
    warehouseAccesses: [
      { warehouseId: "22222222-2222-4222-8222-222222222222", level: "EDIT" },
    ],
  }
})

function RuntimeProbe() {
  const runtime = useOrdersModule()
  return (
    <>
      <output data-testid="orders-runtime">
        {runtime.accessToken}|{runtime.currentUser?.id}|
        {runtime.currentUser?.globalRole}|{runtime.warehouses[0]?.name}|
        {runtime.warehouses[0]?.address}|
        {String(runtime.capabilities.logisticsTaskNavigation)}
      </output>
      <output data-testid="fee-permission">
        {String(
          runtime.canManageBookingChanges(
            "22222222-2222-4222-8222-222222222222"
          )
        )}
        |
        {String(
          runtime.canManageBookingChanges(
            "44444444-4444-4444-8444-444444444444"
          )
        )}
      </output>
    </>
  )
}

describe("VaultPanelOrdersModuleAdapter", () => {
  it("is the single boundary that supplies shell runtime ports to orders", () => {
    render(
      <VaultPanelOrdersModuleAdapter>
        <RuntimeProbe />
      </VaultPanelOrdersModuleAdapter>
    )

    expect(screen.getByTestId("orders-runtime").textContent).toBe(
      "orders-token|11111111-1111-4111-8111-111111111111|WMS_ADMIN|Москва|Складская, 1|false"
    )
  })

  it.each([
    "SYSTEM_ADMIN",
    "WMS_ADMIN",
    "WAREHOUSE_MANAGER",
    "RENTAL_MANAGER",
  ] as const)(
    "supplies the fee UI capability to %s with explicit rental and EDIT access",
    (role: UserGlobalRole) => {
      authRuntime.currentUser!.globalRole = role
      render(
        <VaultPanelOrdersModuleAdapter>
          <RuntimeProbe />
        </VaultPanelOrdersModuleAdapter>
      )
      expect(screen.getByTestId("fee-permission").textContent).toBe(
        "true|false"
      )
    }
  )

  it.each(["VIEW", "EDIT", "MANAGE"] as const)(
    "checks the actual warehouse grant %s",
    (level: WarehouseAccessLevel) => {
      authRuntime.currentUser!.warehouseAccesses[0].level = level
      render(
        <VaultPanelOrdersModuleAdapter>
          <RuntimeProbe />
        </VaultPanelOrdersModuleAdapter>
      )
      expect(screen.getByTestId("fee-permission").textContent).toBe(
        `${level !== "VIEW"}|false`
      )
    }
  )

  it("accepts all-warehouse access only for an otherwise authorized staff user", () => {
    authRuntime.currentUser!.warehouseAccessAll = true
    render(
      <VaultPanelOrdersModuleAdapter>
        <RuntimeProbe />
      </VaultPanelOrdersModuleAdapter>
    )
    expect(screen.getByTestId("fee-permission").textContent).toBe("true|true")
  })

  it.each(["CUSTOMER", "VIEWER"] as const)(
    "never grants staff fee controls to %s",
    (role: UserGlobalRole) => {
      authRuntime.currentUser!.globalRole = role
      authRuntime.currentUser!.warehouseAccessAll = true
      render(
        <VaultPanelOrdersModuleAdapter>
          <RuntimeProbe />
        </VaultPanelOrdersModuleAdapter>
      )
      expect(screen.getByTestId("fee-permission").textContent).toBe(
        "false|false"
      )
    }
  )

  it.each(["rental", "token", "user"] as const)(
    "fails closed without %s",
    (missing) => {
      if (missing === "rental") authRuntime.currentUser!.rentalAccess = false
      if (missing === "token") authRuntime.accessToken = null
      if (missing === "user") authRuntime.currentUser = null
      render(
        <VaultPanelOrdersModuleAdapter>
          <RuntimeProbe />
        </VaultPanelOrdersModuleAdapter>
      )
      expect(screen.getByTestId("fee-permission").textContent).toBe(
        "false|false"
      )
    }
  )
})
