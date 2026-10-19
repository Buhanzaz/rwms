import { useRef, useState } from "react"
import { useMutation, useQuery } from "@tanstack/react-query"
import { Delete02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import {
  listWarehouseSupportLinks,
  replaceWarehouseSupportLinks,
  type WarehouseInfo,
  type WarehouseSupportLinksInfo,
  type WarehouseSupportWeekday,
} from "@/api/warehouse-api"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Field, FieldError, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  getWarehouseMutationError,
  isWarehouseConflict,
} from "@/features/settings/warehouses/warehouse-settings-errors"
import {
  createEmptyWarehouseSupportLinkDraft,
  createWarehouseSupportLinkDraft,
  parseWarehouseSupportLinkDrafts,
  type WarehouseSupportLinkDraft,
} from "@/features/settings/warehouses/warehouse-support-links-form"

const WEEKDAYS: ReadonlyArray<{
  value: WarehouseSupportWeekday
  label: string
}> = [
  { value: "MONDAY", label: "Пн" },
  { value: "TUESDAY", label: "Вт" },
  { value: "WEDNESDAY", label: "Ср" },
  { value: "THURSDAY", label: "Чт" },
  { value: "FRIDAY", label: "Пт" },
  { value: "SATURDAY", label: "Сб" },
  { value: "SUNDAY", label: "Вс" },
]

const CAPABILITIES: ReadonlyArray<{
  field:
    | "allowDrivers"
    | "allowVehicles"
    | "allowInventory"
    | "allowDirectFulfillment"
    | "allowInterwarehouseTransfer"
    | "allowContractorFallback"
  label: string
}> = [
  { field: "allowDrivers", label: "Предоставлять водителей" },
  { field: "allowVehicles", label: "Предоставлять автомобили" },
  { field: "allowInventory", label: "Брать бытовки и имущество" },
  { field: "allowDirectFulfillment", label: "Доставлять напрямую клиенту" },
  {
    field: "allowInterwarehouseTransfer",
    label: "Создавать межскладские перемещения",
  },
  { field: "allowContractorFallback", label: "Предлагать наёмного водителя" },
]

export function WarehouseSupportLinksEditor({
  accessToken,
  warehouse,
  warehouses,
  onSaved,
  onConflict,
}: {
  accessToken: string | null
  warehouse: WarehouseInfo
  warehouses: WarehouseInfo[]
  onSaved: () => Promise<void>
  onConflict: () => Promise<void>
}) {
  const linksQuery = useQuery({
    queryKey: ["warehouse-support-links", warehouse.id],
    queryFn: () => listWarehouseSupportLinks(accessToken, warehouse.id),
    enabled: accessToken !== null,
  })

  if (linksQuery.data === undefined) {
    return (
      <section
        className="border-t pt-5"
        aria-labelledby="warehouse-support-title"
      >
        <h3 id="warehouse-support-title" className="text-sm font-semibold">
          Логистическое обслуживание
        </h3>
        {linksQuery.isError ? (
          <div className="mt-4 flex flex-col items-start gap-3">
            <FieldError role="alert">
              {getWarehouseMutationError(linksQuery.error)}
            </FieldError>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => void linksQuery.refetch()}
            >
              Повторить
            </Button>
          </div>
        ) : (
          <p className="mt-4 text-sm text-muted-foreground">
            Загружаем опорные склады…
          </p>
        )}
      </section>
    )
  }

  return (
    <LoadedWarehouseSupportLinksEditor
      key={`${warehouse.id}:${linksQuery.data.warehouseVersion}`}
      accessToken={accessToken}
      warehouse={warehouse}
      warehouses={warehouses}
      initialCollection={linksQuery.data}
      onSaved={onSaved}
      onConflict={onConflict}
    />
  )
}

function LoadedWarehouseSupportLinksEditor({
  accessToken,
  warehouse,
  warehouses,
  initialCollection,
  onSaved,
  onConflict,
}: {
  accessToken: string | null
  warehouse: WarehouseInfo
  warehouses: WarehouseInfo[]
  initialCollection: WarehouseSupportLinksInfo
  onSaved: () => Promise<void>
  onConflict: () => Promise<void>
}) {
  const nextKey = useRef(1)
  const [drafts, setDrafts] = useState<WarehouseSupportLinkDraft[]>(() =>
    initialCollection.links.map(createWarehouseSupportLinkDraft)
  )
  const [warehouseVersion, setWarehouseVersion] = useState(
    initialCollection.warehouseVersion
  )
  const [formError, setFormError] = useState<string | null>(null)

  const replaceMutation = useMutation({
    mutationFn: async () => {
      const parsed = parseWarehouseSupportLinkDrafts(warehouse.id, drafts)
      if (parsed.links === null) {
        setFormError(parsed.error)
        return null
      }

      setFormError(null)
      return replaceWarehouseSupportLinks(
        accessToken,
        warehouse.id,
        warehouseVersion,
        parsed.links
      )
    },
    onSuccess: async (collection) => {
      if (collection === null) return
      setDrafts(collection.links.map(createWarehouseSupportLinkDraft))
      setWarehouseVersion(collection.warehouseVersion)
      toast.success("Логистическое обслуживание сохранено.")
      await onSaved()
    },
    onError: async (error) => {
      const message = getWarehouseMutationError(error)
      setFormError(message)
      toast.error(message)
      if (isWarehouseConflict(error)) await onConflict()
    },
  })

  function updateDraft<Key extends keyof WarehouseSupportLinkDraft>(
    key: string,
    field: Key,
    value: WarehouseSupportLinkDraft[Key]
  ) {
    setDrafts((current) =>
      current.map((draft) =>
        draft.key === key ? { ...draft, [field]: value } : draft
      )
    )
  }

  function toggleWeekday(
    draft: WarehouseSupportLinkDraft,
    weekday: WarehouseSupportWeekday,
    checked: boolean
  ) {
    updateDraft(
      draft.key,
      "allowedWeekdays",
      checked
        ? [...draft.allowedWeekdays, weekday]
        : draft.allowedWeekdays.filter((value) => value !== weekday)
    )
  }

  const eligibleWarehouses = warehouses.filter(
    (candidate) => candidate.id !== warehouse.id
  )

  return (
    <section
      className="border-t pt-5"
      aria-labelledby="warehouse-support-title"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 id="warehouse-support-title" className="text-sm font-semibold">
            Логистическое обслуживание
          </h3>
          <p className="mt-1 text-xs text-muted-foreground">
            Опорные склады участвуют в подборе водителей, автомобилей и груза
            только в разрешённые дни и интервалы.
          </p>
        </div>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={replaceMutation.isPending}
          onClick={() =>
            setDrafts((current) => [
              ...current,
              createEmptyWarehouseSupportLinkDraft(
                `new-${nextKey.current++}`,
                current.length + 1
              ),
            ])
          }
        >
          Добавить опорный склад
        </Button>
      </div>

      {drafts.length === 0 ? (
        <p className="mt-4 rounded-md border border-dashed p-4 text-sm text-muted-foreground">
          Опорные склады не настроены.
        </p>
      ) : (
        <div className="mt-4 space-y-3">
          {drafts.map((draft, index) => (
            <Card key={draft.key} size="sm">
              <CardHeader className="gap-2">
                <div className="flex items-center justify-between gap-3">
                  <CardTitle className="text-sm">
                    Опорный склад {index + 1}
                  </CardTitle>
                  <Button
                    type="button"
                    size="icon"
                    variant="destructive"
                    aria-label={`Удалить опорный склад ${index + 1}`}
                    title="Удалить"
                    disabled={replaceMutation.isPending}
                    onClick={() =>
                      setDrafts((current) =>
                        current.filter((item) => item.key !== draft.key)
                      )
                    }
                  >
                    <HugeiconsIcon icon={Delete02Icon} aria-hidden="true" />
                  </Button>
                </div>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="grid gap-3 md:grid-cols-[minmax(0,1fr)_8rem]">
                  <Field>
                    <FieldLabel htmlFor={`support-warehouse-${draft.key}`}>
                      Склад
                    </FieldLabel>
                    <Select
                      value={draft.supportWarehouseId || undefined}
                      onValueChange={(value) =>
                        updateDraft(draft.key, "supportWarehouseId", value)
                      }
                    >
                      <SelectTrigger
                        id={`support-warehouse-${draft.key}`}
                        className="w-full"
                      >
                        <SelectValue placeholder="Выберите склад" />
                      </SelectTrigger>
                      <SelectContent>
                        {eligibleWarehouses.map((candidate) => (
                          <SelectItem
                            key={candidate.id}
                            value={candidate.id}
                            disabled={!candidate.active}
                          >
                            {candidate.name} · {candidate.city}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </Field>
                  <Field>
                    <FieldLabel htmlFor={`support-priority-${draft.key}`}>
                      Приоритет
                    </FieldLabel>
                    <Input
                      id={`support-priority-${draft.key}`}
                      type="number"
                      min={1}
                      step={1}
                      value={draft.priority}
                      onChange={(event) =>
                        updateDraft(draft.key, "priority", event.target.value)
                      }
                    />
                  </Field>
                </div>

                <label className="flex items-center gap-2 text-sm">
                  <Checkbox
                    checked={draft.active}
                    onCheckedChange={(checked) =>
                      updateDraft(draft.key, "active", checked === true)
                    }
                  />
                  Связь активна
                </label>

                <div>
                  <p className="text-xs font-medium">Разрешённые ресурсы</p>
                  <div className="mt-2 grid gap-2 md:grid-cols-2">
                    {CAPABILITIES.map((capability) => (
                      <label
                        key={capability.field}
                        className="flex items-center gap-2 text-sm"
                      >
                        <Checkbox
                          checked={draft[capability.field]}
                          onCheckedChange={(checked) =>
                            updateDraft(
                              draft.key,
                              capability.field,
                              checked === true
                            )
                          }
                        />
                        {capability.label}
                      </label>
                    ))}
                  </div>
                </div>

                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="text-xs font-medium">Дни обслуживания</p>
                    {draft.allowedWeekdays.length === 0 ? (
                      <Badge variant="outline">Любой день</Badge>
                    ) : null}
                  </div>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {WEEKDAYS.map((weekday) => (
                      <label
                        key={weekday.value}
                        className="flex items-center gap-1.5 rounded-md border px-2 py-1 text-xs"
                      >
                        <Checkbox
                          checked={draft.allowedWeekdays.includes(
                            weekday.value
                          )}
                          onCheckedChange={(checked) =>
                            toggleWeekday(
                              draft,
                              weekday.value,
                              checked === true
                            )
                          }
                        />
                        {weekday.label}
                      </label>
                    ))}
                  </div>
                </div>

                <div className="grid gap-3 md:grid-cols-2">
                  <Field>
                    <FieldLabel htmlFor={`support-allowed-dates-${draft.key}`}>
                      Разрешённые даты
                    </FieldLabel>
                    <Input
                      id={`support-allowed-dates-${draft.key}`}
                      value={draft.allowedDates}
                      placeholder="2026-09-14, 2026-09-21"
                      onChange={(event) =>
                        updateDraft(
                          draft.key,
                          "allowedDates",
                          event.target.value
                        )
                      }
                    />
                  </Field>
                  <Field>
                    <FieldLabel htmlFor={`support-excluded-dates-${draft.key}`}>
                      Исключения
                    </FieldLabel>
                    <Input
                      id={`support-excluded-dates-${draft.key}`}
                      value={draft.excludedDates}
                      placeholder="2026-09-15"
                      onChange={(event) =>
                        updateDraft(
                          draft.key,
                          "excludedDates",
                          event.target.value
                        )
                      }
                    />
                  </Field>
                  <Field>
                    <FieldLabel htmlFor={`support-service-start-${draft.key}`}>
                      Начало интервала
                    </FieldLabel>
                    <Input
                      id={`support-service-start-${draft.key}`}
                      type="time"
                      value={draft.serviceStart}
                      onChange={(event) =>
                        updateDraft(
                          draft.key,
                          "serviceStart",
                          event.target.value
                        )
                      }
                    />
                  </Field>
                  <Field>
                    <FieldLabel htmlFor={`support-service-end-${draft.key}`}>
                      Окончание интервала
                    </FieldLabel>
                    <Input
                      id={`support-service-end-${draft.key}`}
                      type="time"
                      value={draft.serviceEnd}
                      onChange={(event) =>
                        updateDraft(draft.key, "serviceEnd", event.target.value)
                      }
                    />
                  </Field>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {formError ? <FieldError className="mt-4">{formError}</FieldError> : null}

      <div className="mt-4 flex justify-end">
        <Button
          type="button"
          disabled={replaceMutation.isPending}
          onClick={() => replaceMutation.mutate()}
        >
          {replaceMutation.isPending
            ? "Сохраняем обслуживание…"
            : "Сохранить обслуживание"}
        </Button>
      </div>
    </section>
  )
}
