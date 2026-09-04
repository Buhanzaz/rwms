import { useEffect, useMemo, type ReactNode } from "react"

import type { WarehouseInfo } from "@/api/warehouse-api"
import { isConfigurableObject } from "@/apps/admin/admin-object"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { useWarehouse } from "@/hooks/use-warehouse"

function ObjectKindBadges({ warehouse }: { warehouse: WarehouseInfo }) {
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {warehouse.production ? (
        <Badge variant="secondary">Производство</Badge>
      ) : null}
      {warehouse.mainWarehouse ? (
        <Badge variant="outline">Основной склад</Badge>
      ) : null}
    </div>
  )
}

export function AdminObjectScope({
  children,
  description = "Доступны только объекты с производством, основным складом или обоими признаками.",
  inlineSelector = false,
}: {
  children: (warehouse: WarehouseInfo, selector: ReactNode) => ReactNode
  description?: string | null
  inlineSelector?: boolean
}) {
  const { warehouses, selectedWarehouse, setSelectedWarehouseId } =
    useWarehouse()
  const objects = useMemo(
    () => warehouses.filter(isConfigurableObject),
    [warehouses]
  )
  const selectedObject =
    objects.find((warehouse) => warehouse.id === selectedWarehouse?.id) ?? null

  useEffect(() => {
    if (selectedObject === null && objects[0]) {
      setSelectedWarehouseId(objects[0].id)
    }
  }, [objects, selectedObject, setSelectedWarehouseId])

  if (objects.length === 0) {
    return (
      <Alert>
        <AlertTitle>Нет доступных объектов</AlertTitle>
        <AlertDescription>
          Создайте или активируйте объект с признаком «Производство» или
          «Основной склад».
        </AlertDescription>
      </Alert>
    )
  }

  const selector = (
    <Select
      value={selectedObject?.id ?? ""}
      onValueChange={setSelectedWarehouseId}
    >
      <SelectTrigger
        id="admin-object-selector"
        aria-label={inlineSelector ? "Объект" : undefined}
        className={inlineSelector ? "w-64 max-w-full" : "w-full"}
      >
        <SelectValue placeholder="Выберите объект…" />
      </SelectTrigger>
      <SelectContent
        position="popper"
        className="w-[var(--radix-select-trigger-width)]"
      >
        <SelectGroup>
          {objects.map((warehouse) => (
            <SelectItem key={warehouse.id} value={warehouse.id}>
              <span className="font-mono font-medium">{warehouse.name}</span>
              <span className="text-muted-foreground"> · {warehouse.city}</span>
            </SelectItem>
          ))}
        </SelectGroup>
      </SelectContent>
    </Select>
  )

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4">
      {inlineSelector ? null : (
        <div className="flex flex-col gap-3 rounded-xl border bg-card px-4 py-3 shadow-xs">
          <Field className="w-full flex-1">
            <FieldLabel htmlFor="admin-object-selector">Объект</FieldLabel>
            {selector}
            {description ? (
              <FieldDescription>{description}</FieldDescription>
            ) : null}
          </Field>
          {selectedObject ? (
            <ObjectKindBadges warehouse={selectedObject} />
          ) : null}
        </div>
      )}

      {selectedObject ? children(selectedObject, selector) : null}
    </div>
  )
}
