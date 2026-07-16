import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { OperationsListGrid } from "@/components/operations-list-grid"
import type { InventoryFindingDto } from "@/features/inventory/model/inventory"
import { InventoryReconciliationBadges } from "@/features/inventory/inventory-presentation"
import { inventoryFindingPublicationLabel } from "@/features/inventory/inventory-publication-presentation"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"

const originLabel: Record<InventoryFindingDto["origin"], string> = {
  EXPECTED: "Ожидалась",
  ADDED_NEW: "Добавлена новая",
  ADDED_USED: "Добавлена б/у",
  UNEXPECTED_EXISTING: "Неожиданная",
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
        <span className="text-xs break-all text-muted-foreground">
          Задача: {finding.publishedRepairTaskId}
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

function FindingState({ finding }: { finding: InventoryFindingDto }) {
  return (
    <InventoryReconciliationBadges
      inspected={finding.inspectionStatus !== "NOT_INSPECTED"}
      hasWork={finding.lines.length > 0}
      added={finding.origin === "ADDED_NEW" || finding.origin === "ADDED_USED"}
      missing={finding.reconciliationStatus === "MISSING"}
      conflicts={finding.conflicts.length}
    />
  )
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
}: {
  findings: InventoryFindingDto[]
  canInspect: boolean
  onOpen: (finding: InventoryFindingDto) => void
  showPublication?: boolean
}) {
  return (
    <>
      <div className="hidden min-h-full min-w-0 flex-1 md:block">
        <OperationsListGrid
          className="min-h-full"
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
              getSortValue: (finding) => originLabel[finding.origin],
              render: (finding) => originLabel[finding.origin],
            },
            {
              id: "status",
              label: "Статус бытовки",
              className: "w-44",
              getSortValue: (finding) =>
                finding.currentSnapshot?.status ??
                finding.expectedSnapshot?.status ??
                "",
              render: (finding) => {
                const status =
                  finding.currentSnapshot?.status ??
                  finding.expectedSnapshot?.status
                return status ? <RentalItemStatusBadge status={status} /> : "—"
              },
            },
            {
              id: "state",
              label: "Сверка",
              getSortValue: (finding) =>
                `${finding.inspectionStatus}:${finding.reconciliationStatus}:${finding.conflicts.length}`,
              render: (finding) => <FindingState finding={finding} />,
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
                <dd>{originLabel[finding.origin]}</dd>
                <dt className="text-muted-foreground">Статус</dt>
                <dd>
                  {(finding.currentSnapshot?.status ??
                  finding.expectedSnapshot?.status) ? (
                    <RentalItemStatusBadge
                      status={
                        finding.currentSnapshot?.status ??
                        finding.expectedSnapshot!.status
                      }
                    />
                  ) : (
                    "—"
                  )}
                </dd>
              </dl>
              <FindingState finding={finding} />
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
