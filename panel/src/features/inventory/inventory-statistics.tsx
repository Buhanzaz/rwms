import { useMemo, useState } from "react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import {
  Table,
  TableBody,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { formatMoneyDecimal } from "@/features/repair-estimates/domain/repair-estimate-domain"
import { inventoryRepairMovementCount } from "@/features/inventory/domain/inventory-domain"
import type {
  InventoryFindingDto,
  InventoryStatisticsDto,
} from "@/features/inventory/model/inventory"

function formatDuration(seconds: number) {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return hours > 0 ? `${hours} ч ${minutes} мин` : `${minutes} мин`
}

export function InventoryStatistics({
  statistics,
  findings = [],
  showCounters = true,
}: {
  statistics: InventoryStatisticsDto
  findings?: InventoryFindingDto[]
  showCounters?: boolean
}) {
  const [lineType, setLineType] = useState<"ALL" | "WORK" | "MATERIAL">("ALL")
  const repairMovementCount = inventoryRepairMovementCount(findings)
  const findingsWithLines = useMemo(
    () =>
      findings
        .map((finding) => ({
          ...finding,
          visibleLines: finding.lines.filter(
            (line) => lineType === "ALL" || line.lineType === lineType
          ),
        }))
        .filter((finding) => finding.visibleLines.length > 0),
    [findings, lineType]
  )

  return (
    <section
      className="flex flex-col gap-4"
      aria-label="Статистика инвентаризации"
    >
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
        <Card size="sm">
          <CardHeader>
            <CardTitle>Длительность инвентаризации</CardTitle>
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
        <Card size="sm">
          <CardHeader>
            <CardTitle>Перемещения на ремонт и вывозы</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold">{repairMovementCount}</p>
            <p className="text-sm text-muted-foreground">
              1 перемещение = доставка в ремонт и вывоз
            </p>
          </CardContent>
        </Card>
      </div>

      {showCounters ? (
        <div className="flex flex-wrap gap-2">
          <Badge variant="secondary">
            Ожидалось: {statistics.expectedCount}
          </Badge>
          <Badge variant="secondary">
            Проверено: {statistics.inspectedCount}
          </Badge>
          <Badge variant="secondary">
            Не найдено: {statistics.missingCount}
          </Badge>
          <Badge variant="secondary">Готовы: {statistics.readyCount}</Badge>
          <Badge variant="secondary">
            С работами: {statistics.withWorkCount}
          </Badge>
          <Badge variant="secondary">Добавлено: {statistics.addedCount}</Badge>
          <Badge variant="secondary">
            Конфликты: {statistics.conflictCount}
          </Badge>
        </div>
      ) : null}

      {findings.some((finding) => finding.lines.length > 0) ? (
        <Dialog>
          <DialogTrigger asChild>
            <Button type="button" variant="outline" className="self-start">
              Посмотреть все работы и материалы
            </Button>
          </DialogTrigger>
          <DialogContent className="max-h-[calc(100svh-1rem)] overflow-y-auto sm:max-w-3xl">
            <DialogHeader>
              <DialogTitle>Позиции текущей инвентаризации</DialogTitle>
              <DialogDescription>
                Работы и материалы сгруппированы по бытовкам, в которых они были
                зафиксированы.
              </DialogDescription>
            </DialogHeader>
            <ToggleGroup
              type="single"
              value={lineType}
              variant="outline"
              className="justify-start"
              onValueChange={(value) => {
                if (value === "ALL" || value === "WORK" || value === "MATERIAL")
                  setLineType(value)
              }}
            >
              <ToggleGroupItem value="ALL">Все</ToggleGroupItem>
              <ToggleGroupItem value="WORK">Работы</ToggleGroupItem>
              <ToggleGroupItem value="MATERIAL">Материалы</ToggleGroupItem>
            </ToggleGroup>
            <div className="flex flex-col gap-3">
              {findingsWithLines.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  Для выбранного типа позиций ничего не найдено.
                </p>
              ) : (
                findingsWithLines.map((finding) => (
                  <Card key={finding.id} size="sm">
                    <CardHeader>
                      <CardTitle className="flex flex-wrap items-center justify-between gap-2">
                        <span>Бытовка {finding.cabinNumber}</span>
                        <Badge variant="outline">Инвентаризация</Badge>
                      </CardTitle>
                    </CardHeader>
                    <CardContent className="flex flex-col gap-2">
                      {finding.visibleLines.map((line) => (
                        <div
                          key={line.id}
                          className="flex flex-wrap items-start justify-between gap-3 rounded-full border px-4 py-2 text-sm"
                        >
                          <div className="min-w-0">
                            <p className="font-medium">
                              {line.description || "Без названия"}
                            </p>
                            <p className="text-xs text-muted-foreground">
                              {line.lineType === "WORK" ? "Работа" : "Материал"}
                              {line.lineType === "WORK" &&
                              line.lineComment.trim()
                                ? ` · ${line.lineComment}`
                                : ""}
                            </p>
                          </div>
                          <span className="shrink-0">
                            {line.quantity} {line.unit}
                          </span>
                        </div>
                      ))}
                    </CardContent>
                  </Card>
                ))
              )}
            </div>
          </DialogContent>
        </Dialog>
      ) : null}

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
              <TableFooter>
                <TableRow>
                  <TableCell colSpan={4} className="text-right font-semibold">
                    Итого
                  </TableCell>
                  <TableCell className="font-semibold">
                    {formatMoneyDecimal(statistics.grandTotal)} ₽
                  </TableCell>
                </TableRow>
              </TableFooter>
            </Table>
          </CardContent>
        </Card>
      ) : null}
    </section>
  )
}
