import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { OperationsListGrid } from "@/components/operations-list-grid"
import type { InventoryFindingDto } from "@/features/inventory/model/inventory"
import {
  InventoryFindingStatusBadge,
  type InventoryFindingStatusMode,
} from "@/features/inventory/inventory-presentation"
import { inventoryFindingPublicationLabel } from "@/features/inventory/inventory-publication-presentation"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"

const originLabel: Record<InventoryFindingDto["origin"], string> = {
  EXPECTED: "Ожидалась",
  ADDED_NEW: "Добавлена новая",
  ADDED_USED: "Добавлена б/у",
  UNEXPECTED_EXISTING: "Неожиданная",
}

function findingSourceLabel(finding: InventoryFindingDto) {
  return finding.inspectionSource === "INVENTORY" ||
    finding.inspectionStatus !== "NOT_INSPECTED"
    ? "Инвентаризация"
    : originLabel[finding.origin]
}

function FindingPublication({ finding }: { finding: InventoryFindingDto }) {
  return (
    <div className="flex min-w-0 flex-col items-start gap-1.5 text-sm">
      <Badge
        variant={
          finding.publicationStatus === "PUBLISHED" ? "default" : "secondary"
        }
      >
        {inventoryFindingPublicationLabel[finding.publicationStatus]}
      </Badge>
      {finding.publishedRepairTaskId ? (
        <span className="text-xs text-muted-foreground">
          Задача на ремонт создана
        </span>
      ) : null}
      {finding.publicationError ? (
        <span className="text-xs text-destructive">
          {finding.publicationError}
        </span>
      ) : null}
    </div>
  )
}

function FindingState({
  finding,
  statusMode,
}: {
  finding: InventoryFindingDto
  statusMode: InventoryFindingStatusMode
}) {
  return <InventoryFindingStatusBadge finding={finding} mode={statusMode} />
}

function findingSnapshotStatus(finding: InventoryFindingDto) {
  return (
    finding.currentSnapshot?.status ?? finding.expectedSnapshot?.status ?? null
  )
}

function findingAssetStatusSortValue(
  finding: InventoryFindingDto,
  statusMode: InventoryFindingStatusMode
) {
  if (
    statusMode === "COMPLETION" &&
    finding.publicationStatus === "PUBLISHED" &&
    finding.desiredAssetStatus
  ) {
    return `${finding.desiredAssetStatus}:${finding.movementToRepair}`
  }
  if (statusMode === "COMPLETION" && finding.publicationOperationKey !== null) {
    return "NOT_APPLIED"
  }
  return findingSnapshotStatus(finding) ?? ""
}

function FindingAssetStatus({
  finding,
  statusMode,
}: {
  finding: InventoryFindingDto
  statusMode: InventoryFindingStatusMode
}) {
  if (
    statusMode === "COMPLETION" &&
    finding.publicationStatus === "PUBLISHED" &&
    finding.desiredAssetStatus
  ) {
    return (
      <div className="flex flex-wrap items-center gap-1.5">
        <RentalItemStatusBadge status={finding.desiredAssetStatus} />
        {finding.movementToRepair ? (
          <Badge variant="outline">Перемещение</Badge>
        ) : null}
      </div>
    )
  }

  if (statusMode === "COMPLETION" && finding.publicationOperationKey !== null) {
    return <Badge variant="secondary">Не применён</Badge>
  }

  const status = findingSnapshotStatus(finding)
  return status ? <RentalItemStatusBadge status={status} /> : "—"
}

function FindingAction({
  finding,
  canInspect,
  onOpen,
}: {
  finding: InventoryFindingDto
  canInspect: boolean
  onOpen: (finding: InventoryFindingDto) => void
}) {
  const canOpenInspection =
    finding.currentSnapshot !== null &&
    !finding.conflicts.some((conflict) =>
      ["OTHER_WAREHOUSE", "WRITTEN_OFF", "RENTAL_ITEM_MISSING"].includes(
        conflict.code
      )
    )

  if (!canOpenInspection) {
    return <span className="text-muted-foreground">Только сверка</span>
  }

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      disabled={!canInspect && finding.inspectionStatus === "NOT_INSPECTED"}
      onClick={() => onOpen(finding)}
    >
      {finding.inspectionStatus === "NOT_INSPECTED" ? "Проверить" : "Открыть"}
    </Button>
  )
}

export function InventoryFindingsList({
  findings,
  canInspect,
  onOpen,
  showPublication = false,
  statusMode = "ACTIVE",
}: {
  findings: InventoryFindingDto[]
  canInspect: boolean
  onOpen: (finding: InventoryFindingDto) => void
  showPublication?: boolean
  statusMode?: InventoryFindingStatusMode
}) {
  return (
    <>
      <div
        data-slot="inventory-findings-table"
        className="relative isolate hidden min-w-0 flex-1 bg-background md:block"
      >
        <OperationsListGrid
          items={findings}
          columns={[
            {
              id: "number",
              label: "Номер",
              className: "w-44",
              getSortValue: (finding) => finding.cabinNumber,
              render: (finding) => (
                <span className="font-medium">{finding.cabinNumber}</span>
              ),
            },
            {
              id: "origin",
              label: "Источник",
              className: "w-44",
              getSortValue: findingSourceLabel,
              render: findingSourceLabel,
            },
            {
              id: "status",
              label:
                statusMode === "COMPLETION"
                  ? "Статус по итогу"
                  : "Текущий статус",
              className: "w-44",
              getSortValue: (finding) =>
                findingAssetStatusSortValue(finding, statusMode),
              render: (finding) => (
                <FindingAssetStatus finding={finding} statusMode={statusMode} />
              ),
            },
            {
              id: "state",
              label: "Статус инвентаризации",
              getSortValue: (finding) =>
                `${finding.inspectionStatus}:${finding.reconciliationStatus}:${finding.conflicts.length}`,
              render: (finding) => (
                <FindingState finding={finding} statusMode={statusMode} />
              ),
            },
            ...(showPublication
              ? [
                  {
                    id: "publication",
                    label: "Передача работ",
                    className: "min-w-56",
                    getSortValue: (finding: InventoryFindingDto) =>
                      finding.publicationStatus,
                    render: (finding: InventoryFindingDto) => (
                      <FindingPublication finding={finding} />
                    ),
                  },
                ]
              : []),
            {
              id: "action",
              label: "Действие",
              className: "w-36",
              getSortValue: () => null,
              render: (finding) => (
                <FindingAction
                  finding={finding}
                  canInspect={canInspect}
                  onOpen={onOpen}
                />
              ),
            },
          ]}
        />
      </div>

      <div className="grid gap-3 md:hidden">
        {findings.map((finding) => (
          <Card key={finding.id}>
            <CardHeader>
              <CardTitle>{finding.cabinNumber}</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              <dl className="grid grid-cols-2 gap-2 text-sm">
                <dt className="text-muted-foreground">Источник</dt>
                <dd>{findingSourceLabel(finding)}</dd>
                <dt className="text-muted-foreground">
                  {statusMode === "COMPLETION"
                    ? "Статус по итогу"
                    : "Текущий статус"}
                </dt>
                <dd>
                  <FindingAssetStatus
                    finding={finding}
                    statusMode={statusMode}
                  />
                </dd>
              </dl>
              <FindingState finding={finding} statusMode={statusMode} />
              {showPublication ? (
                <FindingPublication finding={finding} />
              ) : null}
              <FindingAction
                finding={finding}
                canInspect={canInspect}
                onOpen={onOpen}
              />
            </CardContent>
          </Card>
        ))}
      </div>
    </>
  )
}
