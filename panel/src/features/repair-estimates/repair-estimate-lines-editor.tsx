import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  Delete02Icon,
  MinusSignIcon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Field, FieldLabel } from "@/components/ui/field"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import {
  calculateLineTotal,
  createManualEstimateLine,
  normalizeEstimateLine,
} from "@/features/repair-estimates/domain/repair-estimate-domain"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"

type RepairEstimateLinesEditorProps = {
  lines: RepairEstimateLineDto[]
  readOnly: boolean
  mode?: "ESTIMATE" | "TASK"
  catalogOnly?: boolean
  catalogValuesReadOnly?: boolean
  onChange: (lines: RepairEstimateLineDto[]) => void
}

export function RepairEstimateLinesEditor({
  lines,
  readOnly,
  mode = "ESTIMATE",
  catalogOnly = false,
  catalogValuesReadOnly = false,
  onChange,
}: RepairEstimateLinesEditorProps) {
  function updateLine(
    lineId: string,
    update: (line: RepairEstimateLineDto) => RepairEstimateLineDto
  ) {
    onChange(
      lines.map((line) =>
        line.id === lineId ? normalizeEstimateLine(update(line)) : line
      )
    )
  }

  function replaceLine(
    lineId: string,
    update: (line: RepairEstimateLineDto) => RepairEstimateLineDto
  ) {
    onChange(lines.map((line) => (line.id === lineId ? update(line) : line)))
  }

  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="font-heading text-sm font-medium">Смета</h3>
          <p className="text-xs text-muted-foreground">
            Работа, материал, количество и цена сохраняются отдельными полями.
          </p>
        </div>

        {!readOnly && !catalogOnly ? (
          <Button
            type="button"
            variant="outline"
            onClick={() => onChange([...lines, createManualEstimateLine()])}
          >
            <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
            Добавить строку
          </Button>
        ) : null}
      </div>

      {lines.length === 0 ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Строк пока нет</CardTitle>
          </CardHeader>
          <CardContent className="text-muted-foreground">
            {mode === "TASK"
              ? "Для ремонтного задания требуется хотя бы один этап. Добавьте работу или материал из каталога."
              : catalogOnly
                ? "Пустую смету можно сохранить или завершить. Для добавления выберите позицию каталога."
                : "Пустую смету можно сохранить или завершить. Для добавления выберите позицию каталога либо создайте ручную строку."}
          </CardContent>
        </Card>
      ) : (
        <div className="flex flex-col gap-3">
          {lines.map((line, index) => (
            <Card key={line.id} size="sm">
              <CardHeader>
                <CardTitle className="flex items-center justify-between gap-2">
                  <span>Строка {index + 1}</span>
                  {!readOnly ? (
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      aria-label={`Удалить строку ${index + 1}`}
                      onClick={() =>
                        onChange(lines.filter((item) => item.id !== line.id))
                      }
                    >
                      <HugeiconsIcon icon={Delete02Icon} />
                    </Button>
                  ) : null}
                </CardTitle>
              </CardHeader>

              <CardContent>
                <div className="grid gap-3 md:grid-cols-12">
                  <Field className="md:col-span-2" data-disabled={readOnly}>
                    <FieldLabel htmlFor={`line-type-${line.id}`}>
                      Тип
                    </FieldLabel>
                    <Select
                      disabled={
                        readOnly ||
                        catalogOnly ||
                        (catalogValuesReadOnly && line.catalogSnapshot !== null)
                      }
                      value={line.lineType}
                      onValueChange={(value) =>
                        updateLine(line.id, (current) => ({
                          ...current,
                          lineType: value as RepairEstimateLineDto["lineType"],
                        }))
                      }
                    >
                      <SelectTrigger
                        id={`line-type-${line.id}`}
                        aria-label={`Тип строки ${index + 1}`}
                        className="w-full"
                      >
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectGroup>
                          <SelectItem value="WORK">Работа</SelectItem>
                          <SelectItem value="MATERIAL">Материал</SelectItem>
                          <SelectItem value="UNSPECIFIED">
                            Тип не задан сервисом
                          </SelectItem>
                        </SelectGroup>
                      </SelectContent>
                    </Select>
                  </Field>

                  <Field className="md:col-span-5" data-disabled={readOnly}>
                    <FieldLabel htmlFor={`line-description-${line.id}`}>
                      Работа / материал
                    </FieldLabel>
                    <Input
                      id={`line-description-${line.id}`}
                      aria-label={`Работа или материал строки ${index + 1}`}
                      disabled={
                        readOnly ||
                        (catalogValuesReadOnly && line.catalogSnapshot !== null)
                      }
                      value={line.description}
                      onChange={(event) =>
                        updateLine(line.id, (current) => ({
                          ...current,
                          description: event.target.value,
                        }))
                      }
                    />
                  </Field>

                  <Field className="md:col-span-5" data-disabled={readOnly}>
                    <FieldLabel htmlFor={`line-comment-${line.id}`}>
                      Комментарий
                    </FieldLabel>
                    <Textarea
                      id={`line-comment-${line.id}`}
                      aria-label={`Комментарий ${index + 1}`}
                      className="h-9 min-h-9 resize-y"
                      disabled={readOnly}
                      value={line.lineComment}
                      onChange={(event) =>
                        updateLine(line.id, (current) => ({
                          ...current,
                          lineComment: event.target.value,
                        }))
                      }
                    />
                  </Field>

                  <Field className="md:col-span-2" data-disabled={readOnly}>
                    <FieldLabel htmlFor={`line-quantity-${line.id}`}>
                      Количество
                    </FieldLabel>
                    <InputGroup>
                      <InputGroupAddon align="inline-start">
                        <InputGroupButton
                          type="button"
                          size="icon-xs"
                          aria-label={`Уменьшить количество строки ${index + 1}`}
                          disabled={readOnly || line.quantity <= 1}
                          onClick={() =>
                            updateLine(line.id, (current) => ({
                              ...current,
                              quantity: Math.max(1, current.quantity - 1),
                            }))
                          }
                        >
                          <HugeiconsIcon icon={MinusSignIcon} />
                        </InputGroupButton>
                      </InputGroupAddon>
                      <InputGroupInput
                        id={`line-quantity-${line.id}`}
                        aria-label={`Количество строки ${index + 1}`}
                        inputMode="numeric"
                        pattern="[0-9]*"
                        disabled={readOnly}
                        value={line.quantity}
                        onChange={(event) => {
                          const value = event.target.value
                          if (/^\d*$/.test(value)) {
                            updateLine(line.id, (current) => ({
                              ...current,
                              quantity: Math.max(1, Number(value) || 1),
                            }))
                          }
                        }}
                      />
                      <InputGroupAddon align="inline-end">
                        <InputGroupButton
                          type="button"
                          size="icon-xs"
                          aria-label={`Увеличить количество строки ${index + 1}`}
                          disabled={readOnly}
                          onClick={() =>
                            updateLine(line.id, (current) => ({
                              ...current,
                              quantity: current.quantity + 1,
                            }))
                          }
                        >
                          <HugeiconsIcon icon={Add01Icon} />
                        </InputGroupButton>
                      </InputGroupAddon>
                    </InputGroup>
                  </Field>

                  <Field className="md:col-span-2" data-disabled={readOnly}>
                    <FieldLabel htmlFor={`line-unit-${line.id}`}>
                      Единица
                    </FieldLabel>
                    <Input
                      id={`line-unit-${line.id}`}
                      aria-label={`Единица измерения строки ${index + 1}`}
                      disabled={
                        readOnly ||
                        catalogOnly ||
                        (catalogValuesReadOnly && line.catalogSnapshot !== null)
                      }
                      value={line.unit}
                      onChange={(event) =>
                        replaceLine(line.id, (current) => ({
                          ...current,
                          unit: event.target.value,
                        }))
                      }
                      onBlur={() => updateLine(line.id, (current) => current)}
                    />
                  </Field>

                  <Field className="md:col-span-3" data-disabled={readOnly}>
                    <FieldLabel htmlFor={`line-price-${line.id}`}>
                      Цена
                    </FieldLabel>
                    <Input
                      id={`line-price-${line.id}`}
                      aria-label={`Цена строки ${index + 1}`}
                      inputMode="decimal"
                      disabled={
                        readOnly ||
                        (catalogValuesReadOnly && line.catalogSnapshot !== null)
                      }
                      value={line.unitPrice}
                      onChange={(event) => {
                        const value = event.target.value.replace(",", ".")
                        if (value === "" || /^\d+(?:\.\d{0,2})?$/.test(value)) {
                          replaceLine(line.id, (current) => {
                            const unitPrice = value || "0"
                            return {
                              ...current,
                              unitPrice,
                              lineTotal: calculateLineTotal(
                                unitPrice,
                                current.quantity
                              ),
                            }
                          })
                        }
                      }}
                      onBlur={() => updateLine(line.id, (current) => current)}
                    />
                  </Field>

                  <Field className="md:col-span-5">
                    <FieldLabel htmlFor={`line-total-${line.id}`}>
                      Сумма
                    </FieldLabel>
                    <Input
                      id={`line-total-${line.id}`}
                      aria-label={`Сумма строки ${index + 1}`}
                      readOnly
                      value={line.lineTotal}
                    />
                  </Field>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </section>
  )
}
