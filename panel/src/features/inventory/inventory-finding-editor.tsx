import { useCallback, useState } from "react"
import { useMutation } from "@tanstack/react-query"
import { toast } from "sonner"

import { PageToolbar, PageToolbarActions } from "@/components/page-toolbar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { saveInventoryInspection } from "@/features/inventory/api/inventory-api"
import { buildAutoInventoryPlan } from "@/features/inventory/domain/inventory-plan-mapper"
import { InventoryMediaEditor } from "@/features/inventory/inventory-media-editor"
import { reconciliationLabel } from "@/features/inventory/inventory-service-formatters"
import { FrozenPlanView } from "@/features/inventory/inventory-service-ui"
import type {
  InventoryFinding,
  InventoryMediaReference,
  InventorySessionView,
} from "@/features/inventory/model/inventory-service"
import { RepairEstimateCatalogPicker } from "@/features/repair-estimates/repair-estimate-catalog-picker"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"
import { useAuth } from "@/features/auth/use-auth"

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Операция не выполнена"
}

export function InventoryFindingEditor({
  session,
  finding,
  readOnly,
  mediaReadOnly,
  onClose,
  onSaved,
}: {
  session: InventorySessionView
  finding: InventoryFinding
  readOnly: boolean
  mediaReadOnly: boolean
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const { accessToken } = useAuth()
  const [inspection, setInspection] = useState<"READY" | "WORK_STAGED">(
    finding.inspection === "WORK_STAGED" ? "WORK_STAGED" : "READY"
  )
  const [media, setMedia] = useState<InventoryMediaReference[]>(finding.media)
  const [mediaPending, setMediaPending] = useState(true)
  const [catalogLines, setCatalogLines] = useState<RepairEstimateLineDto[]>([])
  const planSelection = buildAutoInventoryPlan(catalogLines)
  const mutation = useMutation({
    mutationFn: () => {
      if (readOnly) {
        throw new Error("Для изменения результата требуется уровень EDIT")
      }
      return saveInventoryInspection({
        accessToken,
        inventoryId: session.id,
        findingId: finding.id,
        expectedSessionRevision: session.sessionRevision,
        expectedFindingRevision: finding.findingRevision,
        inspection,
        media,
        planSelection,
      })
    },
    onSuccess: async () => {
      await onSaved()
      toast.success("Результат осмотра сохранён сервером")
      onClose()
    },
  })
  const setReadyMedia = useCallback(
    (references: InventoryMediaReference[]) => setMedia(references),
    []
  )
  const invalidWork = inspection === "WORK_STAGED" && planSelection === null

  return (
    <div className="flex flex-col gap-4">
      <PageToolbar>
        <Button type="button" variant="outline" onClick={onClose}>
          Назад
        </Button>
        {!readOnly ? (
          <PageToolbarActions>
            <Button
              type="button"
              disabled={mutation.isPending || invalidWork || mediaPending}
              onClick={() => mutation.mutate()}
            >
              {mutation.isPending ? "Сохраняем..." : "Сохранить осмотр"}
            </Button>
          </PageToolbarActions>
        ) : null}
      </PageToolbar>
      <Card>
        <CardHeader>
          <CardTitle>{finding.displayCanonicalNumber}</CardTitle>
          <CardDescription>
            Ревизия результата: {finding.findingRevision}
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-wrap gap-2">
            <Badge variant="secondary">
              {reconciliationLabel[finding.reconciliation]}
            </Badge>
            {finding.expectedSnapshot ? (
              <Badge variant="outline">{finding.expectedSnapshot.status}</Badge>
            ) : null}
          </div>
          {!readOnly ? (
            <Field>
              <FieldLabel id="inventory-inspection-result">
                Результат
              </FieldLabel>
              <ToggleGroup
                type="single"
                value={inspection}
                onValueChange={(value) =>
                  value && setInspection(value as typeof inspection)
                }
                aria-labelledby="inventory-inspection-result"
                variant="outline"
              >
                <ToggleGroupItem value="READY">Готова</ToggleGroupItem>
                <ToggleGroupItem value="WORK_STAGED">
                  Нужны работы
                </ToggleGroupItem>
              </ToggleGroup>
            </Field>
          ) : null}
          {inspection === "WORK_STAGED" && !readOnly ? (
            <Field data-invalid={invalidWork}>
              <FieldLabel>План работ из активного каталога</FieldLabel>
              <FieldDescription>
                Выберите минимум одну работу. Цены, нормативы и маршруты
                зафиксирует maintenance-service из активной версии каталога.
              </FieldDescription>
              <RepairEstimateCatalogPicker
                lines={catalogLines}
                readOnly={false}
                excludeFurniture
                onChange={(lines) =>
                  setCatalogLines(
                    lines.filter(
                      (line) =>
                        line.catalogSnapshot?.nodeType === "WORK" ||
                        line.catalogSnapshot?.nodeType === "MATERIAL"
                    )
                  )
                }
              />
              {catalogLines.length > 0 ? (
                <div className="grid gap-2 sm:grid-cols-2">
                  {catalogLines.map((line) => (
                    <Card key={line.id} size="sm">
                      <CardHeader>
                        <CardTitle>{line.description}</CardTitle>
                        <CardDescription>
                          {line.quantity} {line.unit}
                        </CardDescription>
                      </CardHeader>
                      <CardContent>
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() =>
                            setCatalogLines((current) =>
                              current.filter((item) => item.id !== line.id)
                            )
                          }
                        >
                          Удалить
                        </Button>
                      </CardContent>
                    </Card>
                  ))}
                </div>
              ) : null}
            </Field>
          ) : null}
          <InventoryMediaEditor
            accessToken={accessToken}
            scope={{ ownerId: finding.id, warehouseId: session.warehouseId }}
            readOnly={mediaReadOnly}
            onReadyChange={setReadyMedia}
            onPendingChange={setMediaPending}
          />
          <FrozenPlanView finding={finding} />
        </CardContent>
      </Card>
      {mutation.error ? (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(mutation.error)}
        </p>
      ) : null}
    </div>
  )
}
