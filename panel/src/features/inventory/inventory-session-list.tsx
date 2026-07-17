import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { OperationsListGrid } from "@/components/operations-list-grid"
import type { InventorySessionDto } from "@/features/inventory/model/inventory"

const publicationLabel: Record<
  InventorySessionDto["publicationStatus"],
  string
> = {
  NOT_REQUESTED: "Не запрашивалась",
  PENDING: "Ожидает передачи",
  PARTIAL: "Передана частично",
  PUBLISHED: "Передана",
  FAILED: "Ошибка передачи",
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function sessionStatus(session: InventorySessionDto) {
  return session.status === "ACTIVE" ? "Активна" : "Завершена"
}

function formatDuration(seconds: number) {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return hours > 0 ? `${hours} ч ${minutes} мин` : `${minutes} мин`
}

function sessionSummary(session: InventorySessionDto) {
  if (!session.statistics) return "—"
  return [
    formatDuration(session.statistics.durationSeconds),
    `не найдено: ${session.statistics.missingCount}`,
    `конфликты: ${session.statistics.conflictCount}`,
    `добавлено: ${session.statistics.addedCount}`,
  ].join(" · ")
}

export function InventorySessionList({
  sessions,
  onOpen,
}: {
  sessions: InventorySessionDto[]
  onOpen: (session: InventorySessionDto) => void
}) {
  return (
    <>
      <div className="hidden min-h-full min-w-0 flex-1 md:block">
        <OperationsListGrid
          className="min-h-full"
          items={sessions}
          columns={[
            {
              id: "date",
              label: "Начало",
              className: "w-48",
              getSortValue: (session) => new Date(session.startedAt).getTime(),
              render: (session) => formatDateTime(session.startedAt),
            },
            {
              id: "status",
              label: "Статус",
              className: "w-40",
              getSortValue: (session) => session.status,
              render: (session) => (
                <Badge
                  variant={
                    session.status === "ACTIVE" ? "default" : "secondary"
                  }
                >
                  {sessionStatus(session)}
                </Badge>
              ),
            },
            {
              id: "progress",
              label: "Сверка",
              className: "w-48",
              getSortValue: (session) =>
                session.findings.filter(
                  (finding) => finding.inspectionStatus !== "NOT_INSPECTED"
                ).length,
              render: (session) => {
                const inspected = session.findings.filter(
                  (finding) => finding.inspectionStatus !== "NOT_INSPECTED"
                ).length
                return `${inspected} из ${session.findings.length}`
              },
            },
            {
              id: "summary",
              label: "Итоги",
              className: "min-w-96",
              getSortValue: (session) =>
                session.statistics?.durationSeconds ?? null,
              render: (session) => sessionSummary(session),
            },
            {
              id: "publication",
              label: "Ремонты",
              className: "w-48",
              getSortValue: (session) => session.publicationStatus,
              render: (session) => publicationLabel[session.publicationStatus],
            },
            {
              id: "action",
              label: "Действие",
              className: "w-32",
              getSortValue: () => null,
              render: (session) => (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => onOpen(session)}
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
              <CardTitle className="flex flex-wrap items-center gap-2">
                <span>{formatDateTime(session.startedAt)}</span>
                <Badge
                  variant={
                    session.status === "ACTIVE" ? "default" : "secondary"
                  }
                >
                  {sessionStatus(session)}
                </Badge>
              </CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              <dl className="grid grid-cols-2 gap-2 text-sm">
                <dt className="text-muted-foreground">Проверено</dt>
                <dd>
                  {
                    session.findings.filter(
                      (finding) => finding.inspectionStatus !== "NOT_INSPECTED"
                    ).length
                  }{" "}
                  из {session.findings.length}
                </dd>
                {session.statistics ? (
                  <>
                    <dt className="text-muted-foreground">Длительность</dt>
                    <dd>
                      {formatDuration(session.statistics.durationSeconds)}
                    </dd>
                    <dt className="text-muted-foreground">Не найдено</dt>
                    <dd>{session.statistics.missingCount}</dd>
                    <dt className="text-muted-foreground">Конфликты</dt>
                    <dd>{session.statistics.conflictCount}</dd>
                    <dt className="text-muted-foreground">Добавлено</dt>
                    <dd>{session.statistics.addedCount}</dd>
                  </>
                ) : null}
                <dt className="text-muted-foreground">Ремонты</dt>
                <dd>{publicationLabel[session.publicationStatus]}</dd>
              </dl>
              <Button
                type="button"
                variant="outline"
                onClick={() => onOpen(session)}
              >
                Открыть
              </Button>
            </CardContent>
          </Card>
        ))}
      </div>
    </>
  )
}
