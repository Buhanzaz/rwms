import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { InventoryPublicationPanel } from "@/features/inventory/inventory-publication-panel"
import {
  closeBlockedFindingPublication,
  retryFindingPublication,
} from "@/features/inventory/adapters/http-inventory-adapter"
import type { InventoryFinding } from "@/features/inventory/model/inventory-service"

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: "inventory-token" }),
}))
vi.mock("@/features/inventory/adapters/http-inventory-adapter", () => ({
  retryFindingPublication: vi.fn(),
  closeBlockedFindingPublication: vi.fn(),
}))

const inventoryId = "00000000-0000-4000-8000-000000000720"

function finding(
  id: string,
  state: "TRANSIENT_FAILED" | "BLOCKED",
  publicationRevision: number
): InventoryFinding {
  return {
    id,
    inventoryId,
    findingRevision: 3,
    origin: "EXPECTED",
    inspection: "WORK_STAGED",
    reconciliation: "MATCHED",
    assetId: "00000000-0000-4000-8000-000000000729",
    assetVersion: 2,
    displayCanonicalNumber: state,
    identityMatchKey: state,
    passportObservation: { presence: "ABSENT", value: null },
    equipmentObservation: { presence: "ABSENT", value: null },
    mutationState: "IDLE",
    planFingerprintSha256: "a".repeat(64),
    expectedSnapshot: null,
    frozenPlan: null,
    media: [],
    publication: {
      id: `${id.slice(0, -1)}9`,
      inventoryId,
      findingId: id,
      publicationRevision,
      state,
      sourceRevision: 3,
      attemptCount: 1,
      maintenanceRepairId: null,
      failureCode: "DEPENDENCY_FAILURE",
    },
  }
}

function renderPanel() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  const onChanged = vi.fn().mockResolvedValue(undefined)
  render(
    <QueryClientProvider client={client}>
      <InventoryPublicationPanel
        inventoryId={inventoryId}
        findings={[
          finding(
            "00000000-0000-4000-8000-000000000721",
            "TRANSIENT_FAILED",
            4
          ),
          finding("00000000-0000-4000-8000-000000000722", "BLOCKED", 7),
        ]}
        onChanged={onChanged}
      />
    </QueryClientProvider>
  )
  return onChanged
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("InventoryPublicationPanel", () => {
  it("keeps one retry key after a transient failure", async () => {
    vi.mocked(retryFindingPublication)
      .mockRejectedValueOnce(new Error("Временная ошибка"))
      .mockResolvedValueOnce({} as never)
    renderPanel()

    fireEvent.click(screen.getByRole("button", { name: "Повторить передачу" }))
    await screen.findByText("Временная ошибка")
    fireEvent.click(screen.getByRole("button", { name: "Повторить передачу" }))

    await waitFor(() =>
      expect(retryFindingPublication).toHaveBeenCalledTimes(2)
    )
    const first = vi.mocked(retryFindingPublication).mock.calls[0][0]
    const second = vi.mocked(retryFindingPublication).mock.calls[1][0]
    expect(first.idempotencyKey).toBe(second.idempotencyKey)
    expect(first.reconcileReason).toBeNull()
    expect(first.currentPreconditionSha256).toBeNull()
  })

  it("requires reconciliation evidence for a blocked retry", async () => {
    vi.mocked(retryFindingPublication).mockResolvedValue({} as never)
    renderPanel()

    fireEvent.click(screen.getByRole("button", { name: "Сверить и повторить" }))
    fireEvent.change(screen.getByLabelText("Обоснование"), {
      target: { value: "Повторная сверка источника" },
    })
    fireEvent.change(screen.getByLabelText("SHA-256 текущей предпосылки"), {
      target: { value: "B".repeat(64) },
    })
    fireEvent.click(screen.getByRole("button", { name: "Подтвердить" }))

    await waitFor(() => expect(retryFindingPublication).toHaveBeenCalledOnce())
    expect(retryFindingPublication).toHaveBeenCalledWith(
      expect.objectContaining({
        expectedPublicationRevision: 7,
        reconcileReason: "Повторная сверка источника",
        currentPreconditionSha256: "b".repeat(64),
      })
    )
    expect(closeBlockedFindingPublication).not.toHaveBeenCalled()
  })
})
