import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"
import {
  calculateEstimateTotal,
  formatMoneyDecimal,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"

function MoneyValue({ value }: { value: string }) {
  return <>{formatMoneyDecimal(value)} ₽</>
}

export function RepairEstimateLinesSnapshot({
  lines,
}: {
  lines: RepairEstimateLineDto[]
}) {
  const total = calculateEstimateTotal(lines)

  return (
    <section className="flex min-w-0 flex-col gap-3" aria-label="Состав сметы">
      {lines.length === 0 ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Смета без строк</CardTitle>
          </CardHeader>
          <CardContent className="text-muted-foreground">
            Работы и материалы не добавлены.
          </CardContent>
        </Card>
      ) : (
        lines.map((line, index) => (
          <Card key={line.id} size="sm">
            <CardHeader>
              <CardTitle className="flex flex-wrap items-center gap-2">
                <span>{line.description.trim() || `Строка ${index + 1}`}</span>
                <Badge variant="secondary">
                  {line.lineType === "WORK"
                    ? "Работа"
                    : line.lineType === "MATERIAL"
                      ? "Материал"
                      : "Тип не задан сервисом"}
                </Badge>
              </CardTitle>
            </CardHeader>
            <CardContent>
              <dl className="grid min-w-0 gap-x-4 gap-y-2 sm:grid-cols-[10rem_minmax(0,1fr)]">
                <dt className="text-muted-foreground">Комментарий</dt>
                <dd className="min-w-0 break-words">
                  {line.lineComment.trim() || "—"}
                </dd>
                <dt className="text-muted-foreground">Количество</dt>
                <dd>
                  {line.quantity} {line.unit}
                </dd>
                <dt className="text-muted-foreground">Цена за единицу</dt>
                <dd>
                  <MoneyValue value={line.unitPrice} />
                </dd>
                <dt className="text-muted-foreground">Сумма строки</dt>
                <dd>
                  <MoneyValue value={line.lineTotal} />
                </dd>
              </dl>
            </CardContent>
          </Card>
        ))
      )}

      <Separator />
      <p className="text-right font-medium">
        Итого: <MoneyValue value={total} />
      </p>
    </section>
  )
}
