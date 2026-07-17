import { OperationsListGrid } from "@/components/operations-list-grid"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import type {
  InventoryFinding,
  InventoryFrozenStatistics,
  InventorySessionSummary,
} from "@/features/inventory/model/inventory-service"
import {
  formatDateTime,
  formatMoney,
  inspectionLabel,
  reconciliationLabel,
} from "@/features/inventory/inventory-service-formatters"

const lifecycleLabel = {
  ACTIVE: "Активна",
  COMPLETED: "Завершена",
  CANCELLED: "Отменена",
} as const

export function InventoryUnavailable({ children }: { children: string }) {
  return (
    <Card className="max-w-2xl">
      <CardHeader>
        <CardTitle>Инвентаризация недоступна</CardTitle>
        <CardDescription>{children}</CardDescription>
      </CardHeader>
    </Card>
  )
}

export function InventoryStatisticsView({
  statistics,
}: {
  statistics: InventoryFrozenStatistics
}) {
  const counters = [
    ["Ожидалось", statistics.expectedCount],
    ["Проверено", statistics.inspectedCount],
    ["Не найдено", statistics.missingCount],
    ["Готовы", statistics.readyCount],
    ["С работами", statistics.withWorkCount],
    ["Добавлено", statistics.addedCount],
    ["Конфликты", statistics.conflictCount],
  ] as const
  return (
    <section
      className="flex flex-col gap-3"
      aria-label="Статистика инвентаризации"
    >
      <div className="flex flex-wrap gap-2">
        {counters.map(([label, value]) => (
          <Badge key={label} variant="secondary">
            {label}: {value}
          </Badge>
        ))}
      </div>
      <div className="grid gap-3 sm:grid-cols-3">
        <Card size="sm">
          <CardHeader>
            <CardTitle>Работы</CardTitle>
          </CardHeader>
          <CardContent>{formatMoney(statistics.workTotalMinor)}</CardContent>
        </Card>
        <Card size="sm">
          <CardHeader>
            <CardTitle>Материалы</CardTitle>
          </CardHeader>
          <CardContent>
            {formatMoney(statistics.materialTotalMinor)}
          </CardContent>
        </Card>
        <Card size="sm">
          <CardHeader>
            <CardTitle>Итого</CardTitle>
          </CardHeader>
          <CardContent>{formatMoney(statistics.grandTotalMinor)}</CardContent>
        </Card>
      </div>
    </section>
  )
}

export function SessionCards({
  sessions,
  onOpen,
}: {
  sessions: InventorySessionSummary[]
  onOpen: (session: InventorySessionSummary) => void
}) {
  return (
    <>
      <div className="hidden min-h-0 flex-1 md:flex">
        <OperationsListGrid
          className="min-h-full"
          items={sessions}
          columns={[
            {
              id: "started",
              label: "Начало",
              className: "w-52",
              getSortValue: (item) => item.startedAt,
              render: (item) => formatDateTime(item.startedAt),
            },
            {
              id: "lifecycle",
              label: "Статус",
              className: "w-36",
              getSortValue: (item) => item.lifecycle,
              render: (item) => (
                <Badge
                  variant={
                    item.lifecycle === "ACTIVE" ? "default" : "secondary"
                  }
                >
                  {lifecycleLabel[item.lifecycle]}
                </Badge>
              ),
            },
            {
              id: "progress",
              label: "Проверено",
              className: "w-40",
              getSortValue: (item) => item.inspectedCount,
              render: (item) =>
                `${item.inspectedCount} из ${item.expectedCount}`,
            },
            {
              id: "publication",
              label: "Публикация",
              className: "w-44",
              getSortValue: (item) => item.publicationState,
              render: (item) => item.publicationState,
            },
            {
              id: "action",
              label: "Действие",
              className: "w-28",
              getSortValue: () => null,
              render: (item) => (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => onOpen(item)}
                >
                  Открыть
                </Button>
              ),
            },
          ]}
        />
      </div>
      <div className="grid gap-3 md:hidden">
        {sessions.map((session) => (
          <Card key={session.id}>
            <CardHeader>
              <CardTitle>{formatDateTime(session.startedAt)}</CardTitle>
              <CardDescription>
                Проверено {session.inspectedCount} из {session.expectedCount}
              </CardDescription>
            </CardHeader>
            <CardContent>
              <Badge
                variant={
                  session.lifecycle === "ACTIVE" ? "default" : "secondary"
                }
              >
                {lifecycleLabel[session.lifecycle]}
              </Badge>
            </CardContent>
            <CardFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => onOpen(session)}
              >
                Открыть
              </Button>
            </CardFooter>
          </Card>
        ))}
      </div>
    </>
  )
}

export function FindingList({
  findings,
  onOpen,
}: {
  findings: InventoryFinding[]
  onOpen: (finding: InventoryFinding) => void
}) {
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
      {findings.map((finding) => (
        <Card key={finding.id} size="sm">
          <CardHeader>
            <CardTitle>{finding.displayCanonicalNumber}</CardTitle>
            <CardDescription>{finding.origin}</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-wrap gap-2">
            <Badge
              variant={
                finding.inspection === "NOT_INSPECTED" ? "secondary" : "default"
              }
            >
              {inspectionLabel[finding.inspection]}
            </Badge>
            <Badge variant="outline">
              {reconciliationLabel[finding.reconciliation]}
            </Badge>
            {finding.publication ? (
              <Badge variant="secondary">{finding.publication.state}</Badge>
            ) : null}
          </CardContent>
          <CardFooter>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => onOpen(finding)}
            >
              Открыть
            </Button>
          </CardFooter>
        </Card>
      ))}
    </div>
  )
}

export function FrozenPlanView({ finding }: { finding: InventoryFinding }) {
  if (!finding.frozenPlan) return null
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>Зафиксированный план</CardTitle>
        <CardDescription>
          {finding.frozenPlan.mode} ·{" "}
          {finding.frozenPlan.fingerprintSha256.slice(0, 12)}…
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        {finding.frozenPlan.lines.map((line, index) => (
          <p
            key={`${line.catalogNodeId ?? "manual"}:${index}`}
            className="text-sm"
          >
            {line.description} — {line.quantity} {line.unit},{" "}
            {formatMoney(line.unitPriceMinor)}
          </p>
        ))}
      </CardContent>
    </Card>
  )
}
