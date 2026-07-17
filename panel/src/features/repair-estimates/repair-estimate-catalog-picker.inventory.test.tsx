import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { render, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { RepairEstimateCatalogPicker } from "@/features/repair-estimates/repair-estimate-catalog-picker"
import {
  getOperationalMaintenanceCatalog,
  getOperationalRepairEstimateCatalog,
} from "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api"

vi.mock(
  "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api",
  async (importOriginal) => {
    const actual =
      await importOriginal<
        typeof import("@/features/repair-estimate-catalog/api/repair-estimate-catalog-api")
      >()
    return {
      ...actual,
      getOperationalMaintenanceCatalog: vi.fn().mockResolvedValue({
        nodes: [],
        links: [],
        seedMeta: { nodeCount: 0, linkCount: 0, note: "test" },
      }),
      getOperationalRepairEstimateCatalog: vi.fn(),
    }
  }
)

afterEach(() => vi.clearAllMocks())

describe("inventory maintenance catalog boundary", () => {
  it("loads the warehouse-scoped production reader instead of the browser catalog", async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={client}>
        <RepairEstimateCatalogPicker
          lines={[]}
          readOnly={false}
          warehouseId="00000000-0000-4000-8000-000000000705"
          onChange={vi.fn()}
        />
      </QueryClientProvider>
    )

    await waitFor(() =>
      expect(getOperationalMaintenanceCatalog).toHaveBeenCalledWith(
        "00000000-0000-4000-8000-000000000705"
      )
    )
    expect(getOperationalRepairEstimateCatalog).not.toHaveBeenCalled()
  })
})
