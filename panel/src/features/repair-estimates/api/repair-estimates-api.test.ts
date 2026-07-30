import { beforeEach, describe, expect, it, vi } from "vitest"

const estimateClient = vi.hoisted(() => ({ saveDraft: vi.fn() }))
const catalog = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock(
  "@/features/repair-estimate-catalog/api/repair-estimate-catalog-api",
  async (importOriginal) => ({
    ...(await importOriginal()),
    getOperationalRepairEstimateCatalog: catalog.get,
  })
)

vi.mock(
  "@/features/repair-estimates/adapters/http-maintenance-repair-estimates-adapter",
  () => ({
    HttpMaintenanceRepairEstimatesAdapter: class {
      saveDraft = estimateClient.saveDraft
    },
  })
)

import { saveRepairEstimateDraft } from "@/features/repair-estimates/api/repair-estimates-api"
import type { RepairEstimateEditorDraft } from "@/features/repair-estimates/model/repair-estimate"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const queueBinding = {
  queueId: "00000000-0000-4000-8000-000000000002",
  queueName: "Кузовной ремонт",
  queueKind: "REPAIR" as const,
}

const draft: RepairEstimateEditorDraft = {
  estimateId: null,
  expectedVersion: null,
  rentalItemId: "00000000-0000-4000-8000-000000000003",
  sourceParty: "ООО Арендатор",
  dispatchDate: "2026-07-18",
  comment: "",
  lines: [
    {
      id: "00000000-0000-4000-8000-000000000004",
      sourceLineKey: "custom-work",
      lineType: "WORK",
      description: "Заменить панель",
      lineComment: "",
      unit: "ед",
      quantity: 1,
      normativeMinutes: 30,
      unitPrice: "0.00",
      lineTotal: "0.00",
      catalogSnapshot: null,
      customQueueBinding: queueBinding,
    },
    {
      id: "00000000-0000-4000-8000-000000000005",
      sourceLineKey: "custom-material",
      lineType: "MATERIAL",
      description: "Крепёж",
      lineComment: "",
      unit: "упак.",
      quantity: 2,
      normativeMinutes: 0,
      unitPrice: "100.00",
      lineTotal: "200.00",
      catalogSnapshot: null,
      customQueueBinding: queueBinding,
    },
  ],
  media: [],
  pendingUploads: [],
}

describe("saveRepairEstimateDraft", () => {
  beforeEach(() => {
    vi.clearAllMocks()
    catalog.get.mockResolvedValue({ nodes: [], links: [] })
    estimateClient.saveDraft.mockResolvedValue({ id: "estimate" })
  })

  it("preserves a custom material binding by saving it with its selected work stage", async () => {
    await saveRepairEstimateDraft({ draft, warehouseId })

    expect(estimateClient.saveDraft).toHaveBeenCalledWith(
      expect.objectContaining({
        lines: expect.arrayContaining([
          expect.objectContaining({
            id: draft.lines[1].id,
            lineType: "MATERIAL",
            customQueueBinding: queueBinding,
          }),
        ]),
        taskPlans: [
          expect.objectContaining({
            includedLineIds: [draft.lines[0].id, draft.lines[1].id],
            primaryLineId: draft.lines[0].id,
            queueId: queueBinding.queueId,
          }),
        ],
      })
    )
  })
})
