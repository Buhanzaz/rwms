import { useCallback, useState } from "react"
import { ArrowLeft01Icon, ArrowRight01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"
import { InventoryInspectionDetails } from "@/features/inventory/inventory-inspection-details"
import {
  inventoryFindingMediaOwner,
  type ReadyMediaReference,
} from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"
import {
  RepairEstimateCatalogPicker,
  type RepairEstimateCatalogPager,
} from "@/features/repair-estimates/repair-estimate-catalog-picker"
import { RepairEstimateLinesEditor } from "@/features/repair-estimates/repair-estimate-lines-editor"
import { RepairEstimateLinesSnapshot } from "@/features/repair-estimates/repair-estimate-lines-snapshot"
import { RepairEstimateWorkspaceLayout } from "@/features/repair-estimates/repair-estimate-workspace-layout"
import type { InventoryRepairPlanSnapshotDto } from "@/features/inventory/model/inventory"
import type { InventoryObservation } from "@/features/inventory/model/inventory-service"
import type { RentalItemCreationOptions } from "@/features/rental-items/api/asset-rental-items-api"
import type {
  RepairEstimateCompletionMode,
  RepairEstimateLineDto,
} from "@/features/repair-estimates/model/repair-estimate"

function repairPlanRouteLabel(plan: InventoryRepairPlanSnapshotDto) {
  if (plan.queueName) return plan.queueName
  if (plan.routeQueueKind === "REPAIR") return "Ремонт"
  if (plan.routeQueueKind === "MOVEMENT") return "Перемещение"
  if (plan.routeQueueKind === "HOLDING") return "Удержание"
  return "Маршрут не задан"
}

function InventoryRepairWorkflowSnapshot({
  completionMode,
  movementToRepair,
  plans,
}: {
  completionMode: RepairEstimateCompletionMode | null
  movementToRepair: boolean
  plans: InventoryRepairPlanSnapshotDto[]
}) {
  return (
    <section
      className="flex min-w-0 flex-col gap-3"
      aria-label="Сохранённый план работ"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-heading text-sm font-medium">План работ</h3>
        <div className="flex flex-wrap gap-1">
          {completionMode ? (
            <Badge variant="secondary">
              {completionMode === "AUTO" ? "Автоматически" : "Вручную"}
            </Badge>
          ) : null}
          {movementToRepair ? (
            <Badge variant="outline">На ремонт и с ремонта</Badge>
          ) : null}
          {!movementToRepair ? (
            <Badge variant="outline">Без перемещения</Badge>
          ) : null}
        </div>
      </div>
      {plans.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          Работы не планировались.
        </p>
      ) : (
        plans
          .slice()
          .sort((left, right) => left.sortOrder - right.sortOrder)
          .map((plan, index) => (
            <Card key={plan.id} size="sm">
              <CardHeader>
                <CardTitle className="flex flex-wrap items-center gap-2 text-sm">
                  <span>{index + 1}. Работы</span>
                  <Badge variant="outline">{repairPlanRouteLabel(plan)}</Badge>
                </CardTitle>
              </CardHeader>
              <CardContent className="grid gap-2 text-sm sm:grid-cols-2">
                <p>
                  <span className="text-muted-foreground">Комментарий: </span>
                  {plan.groupComment.trim() || "—"}
                </p>
                <p>
                  <span className="text-muted-foreground">Норматив: </span>
                  {plan.plannedDurationMinutes === null
                    ? "—"
                    : `${plan.plannedDurationMinutes} мин.`}
                </p>
                <p>
                  <span className="text-muted-foreground">Фото: </span>
                  {plan.photoRequired ? "обязательны" : "не требуются"}
                </p>
              </CardContent>
            </Card>
          ))
      )}
    </section>
  )
}

type InventoryInspectionWorkspaceProps = {
  accessToken: string | null
  warehouseId: string
  findingId: string
  cabinNumber: string
  statusLabel: string
  tenant: string | null
  businessDate: string
  comment: string
  passportObservation: InventoryObservation
  passportSnapshot: Record<string, unknown> | null
  passportOptions: RentalItemCreationOptions | null
  passportOptionsLoading: boolean
  passportOptionsError: string | null
  equipmentObservation: InventoryObservation
  lines: RepairEstimateLineDto[]
  media: ReadyMediaReference[]
  repairCompletionMode: RepairEstimateCompletionMode | null
  movementToRepair: boolean
  repairPlans: InventoryRepairPlanSnapshotDto[]
  readOnly: boolean
  coverMediaId: string | null
  message?: string | null
  onCommentChange: (value: string) => void
  onPassportObservationChange: (value: InventoryObservation) => void
  onEditFurniture: () => void
  onRetryPassportOptions: () => void
  onLinesChange: (lines: RepairEstimateLineDto[]) => void
  onMediaChange: (media: ReadyMediaReference[]) => void
  onMediaReadyChange: (ready: boolean) => void
  onCoverMediaIdChange: (mediaId: string | null) => void
}

export function InventoryInspectionWorkspace({
  accessToken,
  warehouseId,
  findingId,
  cabinNumber,
  statusLabel,
  tenant,
  businessDate,
  comment,
  passportObservation,
  passportSnapshot,
  passportOptions,
  passportOptionsLoading,
  passportOptionsError,
  equipmentObservation,
  lines,
  media,
  repairCompletionMode,
  movementToRepair,
  repairPlans,
  readOnly,
  coverMediaId,
  message,
  onCommentChange,
  onPassportObservationChange,
  onEditFurniture,
  onRetryPassportOptions,
  onLinesChange,
  onMediaChange,
  onMediaReadyChange,
  onCoverMediaIdChange,
}: InventoryInspectionWorkspaceProps) {
  const [catalogMessage, setCatalogMessage] = useState<string | null>(null)
  const [catalogPager, setCatalogPager] =
    useState<RepairEstimateCatalogPager | null>(null)
  const handleCatalogPagerChange = useCallback(
    (nextPager: RepairEstimateCatalogPager | null) => {
      setCatalogPager(nextPager)
    },
    []
  )

  return (
    <RepairEstimateWorkspaceLayout
      ariaLabel="Осмотр бытовки при инвентаризации"
      informationDescription="Проверьте результат осмотра и паспорт бытовки."
      catalogDescription="Добавьте работы и материалы, которые нужно передать в ремонт."
      message={
        message || catalogMessage ? (
          <p role="status" className="text-sm text-muted-foreground">
            {message ?? catalogMessage}
          </p>
        ) : null
      }
      photos={
        <ServiceOwnerPhotos
          accessToken={accessToken}
          owner={inventoryFindingMediaOwner(findingId, warehouseId)}
          readOnly={readOnly}
          title="Фотографии осмотра"
          shrinkToContainer
          maxItems={20}
          authoritativeReadyReferences={media}
          coverMediaId={coverMediaId}
          requireCover
          onReadyReferencesChange={onMediaChange}
          onReadyStateChange={onMediaReadyChange}
          onCoverMediaIdChange={onCoverMediaIdChange}
        />
      }
      information={
        <InventoryInspectionDetails
          cabinNumber={cabinNumber}
          statusLabel={statusLabel}
          tenant={tenant}
          businessDate={businessDate}
          comment={comment}
          passportObservation={passportObservation}
          passportSnapshot={passportSnapshot}
          passportOptions={passportOptions}
          passportOptionsLoading={passportOptionsLoading}
          passportOptionsError={passportOptionsError}
          equipmentObservation={equipmentObservation}
          readOnly={readOnly}
          onCommentChange={onCommentChange}
          onPassportObservationChange={onPassportObservationChange}
          onEditFurniture={onEditFurniture}
          onRetryPassportOptions={onRetryPassportOptions}
        />
      }
      estimate={
        readOnly ? (
          <div className="flex min-h-0 flex-col gap-4 overflow-y-auto">
            <RepairEstimateLinesSnapshot lines={lines} />
            <InventoryRepairWorkflowSnapshot
              completionMode={repairCompletionMode}
              movementToRepair={movementToRepair}
              plans={repairPlans}
            />
          </div>
        ) : (
          <RepairEstimateLinesEditor
            lines={lines}
            readOnly={false}
            catalogValuesReadOnly
            accessToken={accessToken}
            mediaOwner={inventoryFindingMediaOwner(findingId, warehouseId)}
            onChange={onLinesChange}
          />
        )
      }
      controls={
        <div className="flex h-full min-h-0 flex-col gap-3">
          <div className="min-h-0 flex-1 overflow-y-auto pr-1">
            <RepairEstimateCatalogPicker
              lines={lines}
              readOnly={readOnly}
              accessToken={accessToken}
              mediaOwner={inventoryFindingMediaOwner(findingId, warehouseId)}
              onPagerChange={handleCatalogPagerChange}
              onChange={(nextLines) => {
                onLinesChange(nextLines)
                setCatalogMessage("Позиция добавлена в результат осмотра.")
              }}
            />
          </div>

          {!readOnly ? (
            <>
              <Separator />
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  variant={catalogPager?.canGoBack ? "default" : "outline"}
                  size="icon-sm"
                  aria-label="Предыдущая страница каталога"
                  disabled={!catalogPager?.canGoBack}
                  onClick={() => catalogPager?.goBack()}
                >
                  <HugeiconsIcon
                    icon={ArrowLeft01Icon}
                    data-icon="inline-start"
                  />
                </Button>
                <Button
                  type="button"
                  variant={catalogPager?.canGoForward ? "default" : "outline"}
                  size="icon-sm"
                  aria-label="Следующая страница каталога"
                  disabled={!catalogPager?.canGoForward}
                  onClick={() => catalogPager?.goForward()}
                >
                  <HugeiconsIcon
                    icon={ArrowRight01Icon}
                    data-icon="inline-start"
                  />
                </Button>
              </div>
            </>
          ) : null}
        </div>
      }
    />
  )
}
