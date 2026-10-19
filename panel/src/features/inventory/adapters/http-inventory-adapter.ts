import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"
import type {
  FurnitureReviewView,
  InventoryCabinDispositionReview,
  ConfirmInventoryReturnsRequest,
  ConfirmInventoryShipmentsRequest,
  InventoryCompletionPreview,
  InventoryFinalPlan,
  InventoryFinding,
  InventoryFindingPage,
  InventoryMediaReference,
  InventoryObservation,
  OutcomeRecalculation,
  InventoryNumberResolution,
  InventoryPlanSelection,
  InventoryPublicationIntent,
  InventoryRegistryReview,
  InventorySessionDetail,
  InventorySessionPage,
  InventorySessionView,
  InventoryFrozenStatistics,
  InventoryPlanningSettings,
  PrepareInventoryFinalPlanRequest,
  SaveFurnitureReviewRequest,
  StartFurnitureReviewRequest,
  UpdateInventoryFinalPlanRequest,
  UpdateInventoryPlanningSettingsRequest,
  InventoryStatisticsPage,
  InventoryStatisticsSummary,
  RefreshInventorySessionRequest,
  RecalculateInventoryOutcomeRequest,
} from "@/features/inventory/model/inventory-service"

function requireInventoryAccessToken(accessToken: string | null) {
  if (!accessToken?.trim()) {
    throw new Error("Для инвентаризации требуется авторизация")
  }
  return accessToken
}

function endpoint(path: string) {
  return `${getGatewayRuntimeConfig().inventoryApiBaseUrl}/v1${path}`
}

function commandHeaders(idempotencyKey: string) {
  return { "Idempotency-Key": idempotencyKey }
}

function revisionExpectations(findings: InventoryFinding[]) {
  return findings.map((finding) => ({
    findingId: finding.id,
    expectedFindingRevision: finding.findingRevision,
  }))
}

async function listAllFindings(accessToken: string, inventoryId: string) {
  const content: InventoryFinding[] = []
  let page = 0
  let totalPages = 1
  while (page < totalPages) {
    const response = await bearerRequest<InventoryFindingPage>(
      accessToken,
      endpoint(
        `/sessions/${encodeURIComponent(inventoryId)}/findings?page=${page}&size=200&sort=createdAt%2Casc`
      )
    )
    content.push(...response.content)
    totalPages = response.page.totalPages
    page += 1
  }
  return content
}

export async function listInventorySessions(
  accessToken: string | null,
  warehouseId: string,
  page = 0
) {
  return bearerRequest<InventorySessionPage>(
    requireInventoryAccessToken(accessToken),
    endpoint(
      `/sessions?warehouseId=${encodeURIComponent(warehouseId)}&page=${page}&size=50&sort=startedAt%2Cdesc`
    )
  )
}

export function getInventoryPlanningSettings(
  accessToken: string | null,
  warehouseId: string
) {
  return bearerRequest<InventoryPlanningSettings>(
    requireInventoryAccessToken(accessToken),
    endpoint(`/planning-settings/${encodeURIComponent(warehouseId)}`)
  )
}

export function updateInventoryPlanningSettings(input: {
  accessToken: string | null
  warehouseId: string
  request: UpdateInventoryPlanningSettingsRequest
}) {
  return bearerRequest<InventoryPlanningSettings>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(`/planning-settings/${encodeURIComponent(input.warehouseId)}`),
    {
      method: "PUT",
      body: JSON.stringify(input.request),
    }
  )
}

export async function getActiveInventorySession(
  accessToken: string | null,
  warehouseId: string
) {
  const token = requireInventoryAccessToken(accessToken)
  const detail = await bearerRequest<InventorySessionDetail | undefined>(
    token,
    endpoint(`/sessions/active?warehouseId=${encodeURIComponent(warehouseId)}`)
  )
  if (detail === undefined) return null

  const findings = await listAllFindings(token, detail.id)
  return { ...detail, findings } satisfies InventorySessionView
}

async function getInventorySessionByUrl(accessToken: string, path: string) {
  const detail = await bearerRequest<InventorySessionDetail>(
    accessToken,
    endpoint(path)
  )
  const findings = await listAllFindings(accessToken, detail.id)
  return { ...detail, findings } satisfies InventorySessionView
}

export function getInventorySession(
  accessToken: string | null,
  inventoryId: string
) {
  return getInventorySessionByUrl(
    requireInventoryAccessToken(accessToken),
    `/sessions/${encodeURIComponent(inventoryId)}`
  )
}

export function reviewInventoryRegistry(input: {
  accessToken: string | null
  session: InventorySessionView
}) {
  return bearerRequest<InventoryRegistryReview>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.session.id)}/registry-review`
    ),
    {
      method: "POST",
      body: JSON.stringify({
        expectedSessionRevision: input.session.sessionRevision,
        findingRevisions: revisionExpectations(input.session.findings),
      }),
    }
  )
}

export function refreshInventorySession(input: {
  accessToken: string | null
  inventoryId: string
  request: RefreshInventorySessionRequest
  idempotencyKey: string
}) {
  return bearerRequest<InventorySessionDetail>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(`/sessions/${encodeURIComponent(input.inventoryId)}/refresh`),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify(input.request),
    }
  )
}

export function getInventoryPreliminaryStatistics(
  accessToken: string | null,
  inventoryId: string
) {
  return bearerRequest<InventoryFrozenStatistics>(
    requireInventoryAccessToken(accessToken),
    endpoint(`/sessions/${encodeURIComponent(inventoryId)}/statistics-preview`)
  )
}

export function startInventorySession(
  accessToken: string | null,
  warehouseId: string,
  idempotencyKey: string
) {
  return bearerRequest<InventorySessionDetail>(
    requireInventoryAccessToken(accessToken),
    endpoint("/sessions"),
    {
      method: "POST",
      headers: commandHeaders(idempotencyKey),
      body: JSON.stringify({ warehouseId }),
    }
  )
}

export function resolveInventoryNumber(input: {
  accessToken: string | null
  inventoryId: string
  expectedSessionRevision: number
  submittedNumber: string
  idempotencyKey: string
}) {
  return bearerRequest<InventoryNumberResolution>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.inventoryId)}/number-resolutions`
    ),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify({
        expectedSessionRevision: input.expectedSessionRevision,
        submittedNumber: input.submittedNumber,
      }),
    }
  )
}

export function createAndAttachInventoryAsset(input: {
  accessToken: string | null
  inventoryId: string
  findingId: string
  expectedSessionRevision: number
  expectedFindingRevision: number
  origin: "ADDED_NEW" | "ADDED_USED"
  displayCanonicalNumber: string
  safePassport: Record<string, unknown>
  idempotencyKey: string
}) {
  return bearerRequest<InventoryFinding>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.inventoryId)}/findings/${encodeURIComponent(input.findingId)}/assets`
    ),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify({
        expectedSessionRevision: input.expectedSessionRevision,
        expectedFindingRevision: input.expectedFindingRevision,
        sourceRevision: 1,
        origin: input.origin,
        displayCanonicalNumber: input.displayCanonicalNumber,
        safePassport: input.safePassport,
      }),
    }
  )
}

export function saveInventoryInspection(input: {
  accessToken: string | null
  inventoryId: string
  findingId: string
  expectedSessionRevision: number
  expectedFindingRevision: number
  inspection: "READY" | "WORK_STAGED"
  comment: string
  passportObservation: InventoryObservation
  equipmentObservation: InventoryObservation
  media: InventoryMediaReference[]
  coverMediaId: string | null
  planSelection: InventoryPlanSelection
}) {
  return bearerRequest<InventoryFinding>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.inventoryId)}/findings/${encodeURIComponent(input.findingId)}/inspection`
    ),
    {
      method: "PUT",
      body: JSON.stringify({
        expectedSessionRevision: input.expectedSessionRevision,
        expectedFindingRevision: input.expectedFindingRevision,
        inspection: input.inspection,
        comment: input.comment,
        passportObservation: input.passportObservation,
        equipmentObservation: input.equipmentObservation,
        media: input.media,
        coverMediaId: input.coverMediaId,
        planSelection:
          input.inspection === "READY" ? null : input.planSelection,
      }),
    }
  )
}

export function resolveInventoryFindingConflict(input: {
  accessToken: string | null
  inventoryId: string
  findingId: string
  expectedSessionRevision: number
  expectedFindingRevision: number
  strategy: "ACCEPT_REGISTRY" | "KEEP_INSPECTION"
  reason: string | null
}) {
  return bearerRequest<InventoryFinding>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.inventoryId)}/findings/${encodeURIComponent(input.findingId)}/conflict-resolution`
    ),
    {
      method: "PUT",
      body: JSON.stringify({
        expectedSessionRevision: input.expectedSessionRevision,
        expectedFindingRevision: input.expectedFindingRevision,
        strategy: input.strategy,
        reason: input.reason,
      }),
    }
  )
}

export function startFurnitureReview(input: {
  accessToken: string | null
  inventoryId: string
  request: StartFurnitureReviewRequest
  idempotencyKey: string
}) {
  return bearerRequest<FurnitureReviewView>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.inventoryId)}/furniture-review/start`
    ),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify(input.request),
    }
  )
}

export function getCabinDispositionReview(
  accessToken: string | null,
  inventoryId: string
) {
  return bearerRequest<InventoryCabinDispositionReview>(
    requireInventoryAccessToken(accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(inventoryId)}/cabin-disposition-review`
    )
  )
}

export function confirmCabinDispositionReturns(input: {
  accessToken: string | null
  inventoryId: string
  request: ConfirmInventoryReturnsRequest
  idempotencyKey: string
}) {
  return bearerRequest<InventoryCabinDispositionReview>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.inventoryId)}/cabin-disposition-review/returns/confirm`
    ),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify(input.request),
    }
  )
}

export function confirmCabinDispositionShipments(input: {
  accessToken: string | null
  inventoryId: string
  request: ConfirmInventoryShipmentsRequest
  idempotencyKey: string
}) {
  return bearerRequest<InventoryCabinDispositionReview>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.inventoryId)}/cabin-disposition-review/shipments/confirm`
    ),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify(input.request),
    }
  )
}

export function getFurnitureReview(
  accessToken: string | null,
  inventoryId: string
) {
  return bearerRequest<FurnitureReviewView>(
    requireInventoryAccessToken(accessToken),
    endpoint(`/sessions/${encodeURIComponent(inventoryId)}/furniture-review`)
  )
}

export function saveFurnitureReview(input: {
  accessToken: string | null
  inventoryId: string
  request: SaveFurnitureReviewRequest
}) {
  return bearerRequest<FurnitureReviewView>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.inventoryId)}/furniture-review`
    ),
    {
      method: "PUT",
      body: JSON.stringify(input.request),
    }
  )
}

export function prepareInventoryFinalPlan(input: {
  accessToken: string | null
  inventoryId: string
  request: PrepareInventoryFinalPlanRequest
  idempotencyKey: string
}) {
  return bearerRequest<InventoryFinalPlan>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.inventoryId)}/final-plan/prepare`
    ),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify(input.request),
    }
  )
}

export function getInventoryFinalPlan(
  accessToken: string | null,
  inventoryId: string
) {
  return bearerRequest<InventoryFinalPlan>(
    requireInventoryAccessToken(accessToken),
    endpoint(`/sessions/${encodeURIComponent(inventoryId)}/final-plan`)
  )
}

export function updateInventoryFinalPlan(input: {
  accessToken: string | null
  inventoryId: string
  request: UpdateInventoryFinalPlanRequest
  idempotencyKey: string
}) {
  return bearerRequest<InventoryFinalPlan>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(`/sessions/${encodeURIComponent(input.inventoryId)}/final-plan`),
    {
      method: "PUT",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify(input.request),
    }
  )
}

export function previewInventoryCompletion(input: {
  accessToken: string | null
  session: InventorySessionView
  finalPlanVersion: number
  finalPlanSha256: string
  idempotencyKey: string
}) {
  return bearerRequest<InventoryCompletionPreview>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.session.id)}/completion-preview`
    ),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify({
        expectedSessionRevision: input.session.sessionRevision,
        findingRevisions: revisionExpectations(input.session.findings),
        finalPlanVersion: input.finalPlanVersion,
        finalPlanSha256: input.finalPlanSha256,
      }),
    }
  )
}

export function completeInventorySession(input: {
  accessToken: string | null
  preview: InventoryCompletionPreview
  idempotencyKey: string
}) {
  return bearerRequest<InventorySessionDetail>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.preview.inventoryId)}/complete`
    ),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify({
        expectedSessionRevision: input.preview.sessionRevision,
        findingRevisions: input.preview.findingRevisions,
        acknowledgementSha256: input.preview.acknowledgementSha256,
        validationSha256: input.preview.validationSha256,
        finalPlanVersion: input.preview.finalPlanVersion,
        finalPlanSha256: input.preview.finalPlanSha256,
      }),
    }
  )
}

export function cancelInventorySession(input: {
  accessToken: string | null
  inventoryId: string
  expectedSessionRevision: number
  reason: string
  idempotencyKey: string
}) {
  return bearerRequest<InventorySessionDetail>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(`/sessions/${encodeURIComponent(input.inventoryId)}/cancel`),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify({
        expectedSessionRevision: input.expectedSessionRevision,
        reason: input.reason,
      }),
    }
  )
}

export function recalculateInventoryOutcome(input: {
  accessToken: string | null
  inventoryId: string
  request: RecalculateInventoryOutcomeRequest
  idempotencyKey: string
}) {
  return bearerRequest<OutcomeRecalculation>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.inventoryId)}/outcome/recalculate`
    ),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify(input.request),
    }
  )
}

export function retryFindingPublication(input: {
  accessToken: string | null
  inventoryId: string
  findingId: string
  expectedPublicationRevision: number
  reconcileReason: string | null
  currentPreconditionSha256: string | null
  idempotencyKey: string
}) {
  return bearerRequest<InventoryPublicationIntent>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.inventoryId)}/findings/${encodeURIComponent(input.findingId)}/publication/retry`
    ),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify({
        expectedPublicationRevision: input.expectedPublicationRevision,
        reconcileReason: input.reconcileReason,
        currentPreconditionSha256: input.currentPreconditionSha256,
      }),
    }
  )
}

export function closeBlockedFindingPublication(input: {
  accessToken: string | null
  inventoryId: string
  findingId: string
  expectedPublicationRevision: number
  reason: string
  idempotencyKey: string
}) {
  return bearerRequest<InventoryPublicationIntent>(
    requireInventoryAccessToken(input.accessToken),
    endpoint(
      `/sessions/${encodeURIComponent(input.inventoryId)}/findings/${encodeURIComponent(input.findingId)}/publication/close`
    ),
    {
      method: "POST",
      headers: commandHeaders(input.idempotencyKey),
      body: JSON.stringify({
        expectedPublicationRevision: input.expectedPublicationRevision,
        reason: input.reason,
      }),
    }
  )
}

export function getInventoryStatisticsSummary(
  accessToken: string | null,
  warehouseId: string
) {
  return bearerRequest<InventoryStatisticsSummary>(
    requireInventoryAccessToken(accessToken),
    endpoint(
      `/statistics/summary?warehouseId=${encodeURIComponent(warehouseId)}`
    )
  )
}

export function listInventorySessionStatistics(
  accessToken: string | null,
  warehouseId: string,
  page = 0
) {
  return bearerRequest<InventoryStatisticsPage>(
    requireInventoryAccessToken(accessToken),
    endpoint(
      `/statistics/sessions?warehouseId=${encodeURIComponent(warehouseId)}&page=${page}&size=50&sort=completedAt%2Cdesc`
    )
  )
}
