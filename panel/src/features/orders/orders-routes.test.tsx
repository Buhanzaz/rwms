import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({
    accessToken: "orders-token",
    currentUser: {
      id: "11111111-1111-4111-8111-111111111111",
      globalRole: "WMS_ADMIN",
    },
  }),
}))

vi.mock("@/hooks/use-warehouse", () => ({
  useWarehouse: () => ({
    warehouses: [
      {
        id: "22222222-2222-4222-8222-222222222222",
        serviceId: "22222222-2222-4222-8222-222222222222",
        version: 3,
        code: "MSK",
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

function RuntimeProbe() {
  const runtime = useOrdersModule()
  return (
    <output data-testid="orders-runtime">
      {runtime.accessToken}|{runtime.currentUser?.id}|
      {runtime.currentUser?.globalRole}|{runtime.warehouses[0]?.code}|
      {runtime.warehouses[0]?.address}
    </output>
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
      "orders-token|11111111-1111-4111-8111-111111111111|WMS_ADMIN|MSK|Складская, 1"
    )
  })
})
