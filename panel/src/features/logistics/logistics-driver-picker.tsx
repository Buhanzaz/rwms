import { useMemo } from "react"
import { useQuery } from "@tanstack/react-query"

import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from "@/components/ui/field"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  listRepairWorkerGroups,
  repairWorkerGroupsQueryKey,
} from "@/features/repair-tasks/api/repair-worker-directory-api"
import type { RepairTaskWorkerSnapshotDto } from "@/features/repair-tasks/model/repair-task"

const DRIVER_DIRECTORY_QUERY = {
  queueId: null,
  routeQueueKind: "MOVEMENT" as const,
  purpose: "DRIVER_DIRECTORY" as const,
}

export function LogisticsDriverPicker({
  accessToken,
  disabled = false,
  id,
  required = true,
  value,
  warehouseId,
  onChange,
}: {
  accessToken: string
  disabled?: boolean
  id: string
  required?: boolean
  value: RepairTaskWorkerSnapshotDto | null
  warehouseId: string
  onChange: (next: RepairTaskWorkerSnapshotDto | null) => void
}) {
  const groupsQuery = useQuery({
    queryKey: repairWorkerGroupsQueryKey({
      warehouseId,
      ...DRIVER_DIRECTORY_QUERY,
    }),
    queryFn: () =>
      listRepairWorkerGroups(
        {
          warehouseId,
          ...DRIVER_DIRECTORY_QUERY,
        },
        accessToken
      ),
    enabled: Boolean(accessToken && warehouseId),
  })
  const drivers = useMemo(
    () =>
      Array.from(
        new Map(
          (groupsQuery.data ?? [])
            .filter((group) => group.active)
            .flatMap((group) => group.members)
            .map((member) => [member.id, member])
        ).values()
      ),
    [groupsQuery.data]
  )

  return (
    <Field data-invalid={groupsQuery.isError || undefined}>
      <FieldLabel htmlFor={id}>Водитель</FieldLabel>
      <Select
        value={value?.id ?? ""}
        disabled={disabled || groupsQuery.isLoading || drivers.length === 0}
        onValueChange={(driverId) =>
          onChange(drivers.find((driver) => driver.id === driverId) ?? null)
        }
      >
        <SelectTrigger id={id} aria-required={required || undefined}>
          <SelectValue placeholder="Выберите водителя" />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            {drivers.map((driver) => (
              <SelectItem key={driver.id} value={driver.id}>
                {driver.name}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
      {groupsQuery.isFetching ? (
        <FieldDescription>Загружаем водителей…</FieldDescription>
      ) : null}
      {groupsQuery.isSuccess && drivers.length === 0 ? (
        <FieldDescription>
          В конфигурации склада нет доступных водителей: добавьте активного
          водителя в бригаду класса «Водитель».
        </FieldDescription>
      ) : null}
      {groupsQuery.isError ? (
        <FieldError>
          {groupsQuery.error instanceof Error
            ? groupsQuery.error.message
            : "Не удалось загрузить справочник водителей."}
        </FieldError>
      ) : null}
    </Field>
  )
}
