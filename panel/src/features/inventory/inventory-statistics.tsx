import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatMoneyDecimal } from "@/features/repair-estimates/domain/repair-estimate-domain"
import type { InventoryStatisticsDto } from "@/features/inventory/model/inventory"

function formatDuration(seconds: number) {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return hours > 0 ? `${hours} ч ${minutes} мин` : `${minutes} мин`
}

export function InventoryStatistics({
  statistics,
}: {
  statistics: InventoryStatisticsDto
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
      className="flex flex-col gap-4"
      aria-label="Статистика инвентаризации"
    >
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Card size="sm">
          <CardHeader>
            <CardTitle>Длительность</CardTitle>
          </CardHeader>
          <CardContent className="text-2xl font-semibold">
            {formatDuration(statistics.durationSeconds)}
          </CardContent>
        </Card>
        <Card size="sm">
          <CardHeader>
            <CardTitle>Работы</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold">{statistics.workLineCount}</p>
            <p className="text-sm text-muted-foreground">
              {formatMoneyDecimal(statistics.workTotal)} ₽
            </p>
          </CardContent>
        </Card>
        <Card size="sm">
          <CardHeader>
            <CardTitle>Материалы</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold">
              {statistics.materialLineCount}
            </p>
            <p className="text-sm text-muted-foreground">
              {formatMoneyDecimal(statistics.materialTotal)} ₽
            </p>
          </CardContent>
        </Card>
        <Card size="sm">
          <CardHeader>
            <CardTitle>Итого</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold">
              {formatMoneyDecimal(statistics.grandTotal)} ₽
            </p>
            <p className="text-sm text-muted-foreground">
              {statistics.plannedDurationMinutes} мин по нормативу
            </p>
          </CardContent>
        </Card>
      </div>

      <div className="flex flex-wrap gap-2">
        {counters.map(([label, value]) => (
          <Badge key={label} variant="secondary">
            {label}: {value}
          </Badge>
        ))}
      </div>

      {statistics.aggregates.length > 0 ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Итоговые позиции</CardTitle>
          </CardHeader>
          <CardContent>
            <Table className="min-w-[40rem]">
              <TableHeader>
                <TableRow>
                  <TableHead>Тип</TableHead>
                  <TableHead>Позиция</TableHead>
                  <TableHead>Кол-во</TableHead>
                  <TableHead>Цена</TableHead>
                  <TableHead>Сумма</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {statistics.aggregates.map((aggregate) => (
                  <TableRow key={aggregate.key}>
                    <TableCell>
                      {aggregate.lineType === "WORK" ? "Работа" : "Материал"}
                    </TableCell>
                    <TableCell>{aggregate.description}</TableCell>
                    <TableCell>
                      {aggregate.quantity} {aggregate.unit}
                    </TableCell>
                    <TableCell>
                      {formatMoneyDecimal(aggregate.unitPrice)} ₽
                    </TableCell>
                    <TableCell>
                      {formatMoneyDecimal(aggregate.total)} ₽
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      ) : null}
    </section>
  )
}
