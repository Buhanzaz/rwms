import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { OperationsListGrid } from "@/components/operations-list-grid"
import type { InventorySessionSummaryDto } from "@/features/inventory/model/inventory"

const publicationLabel: Record<
  InventorySessionSummaryDto["publicationStatus"],
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

function sessionStatus(session: InventorySessionSummaryDto) {
  if (session.status === "ACTIVE") return "Активна"
  if (session.status === "CANCELLED") return "Отменена"
  return "Завершена"
}

function sessionStatusVariant(session: InventorySessionSummaryDto) {
  if (session.status === "ACTIVE") return "default" as const
  if (session.status === "CANCELLED") return "destructive" as const
  return "secondary" as const
}

function sessionPublication(session: InventorySessionSummaryDto) {
  return session.status === "CANCELLED"
    ? "Не применяется"
    : publicationLabel[session.publicationStatus]
}

function sessionSummary(session: InventorySessionSummaryDto) {
  return [
    `ожидалось: ${session.expectedCount}`,
    `записей: ${session.findingCount}`,
    `проверено: ${session.inspectedCount}`,
  ].join(" · ")
}

export function InventorySessionList({
  sessions,
  onOpen,
}: {
  sessions: InventorySessionSummaryDto[]
  onOpen: (session: InventorySessionSummaryDto) => void
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
              id: "author",
              label: "Автор",
              className: "w-48",
              getSortValue: (session) => session.author.displayName,
              render: (session) => session.author.displayName,
            },
            {
              id: "status",
              label: "Статус",
              className: "w-40",
              getSortValue: (session) => session.status,
              render: (session) => (
                <Badge variant={sessionStatusVariant(session)}>
                  {sessionStatus(session)}
                </Badge>
              ),
            },
            {
              id: "progress",
              label: "Сверка",
              className: "w-48",
              getSortValue: (session) => session.inspectedCount,
              render: (session) =>
                `${session.inspectedCount} из ${session.findingCount}`,
            },
            {
              id: "summary",
              label: "Объём",
              className: "min-w-96",
              getSortValue: (session) => session.expectedCount,
              render: (session) => sessionSummary(session),
            },
            {
              id: "publication",
              label: "Ремонты",
              className: "w-48",
              getSortValue: (session) => session.publicationStatus,
              render: sessionPublication,
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
                <Badge variant={sessionStatusVariant(session)}>
                  {sessionStatus(session)}
                </Badge>
              </CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              <dl className="grid grid-cols-2 gap-2 text-sm">
                <dt className="text-muted-foreground">Автор</dt>
                <dd>{session.author.displayName}</dd>
                <dt className="text-muted-foreground">Проверено</dt>
                <dd>
                  {session.inspectedCount} из {session.findingCount}
                </dd>
                <dt className="text-muted-foreground">Ожидалось</dt>
                <dd>{session.expectedCount}</dd>
                <dt className="text-muted-foreground">Ремонты</dt>
                <dd>{sessionPublication(session)}</dd>
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
