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

const UNKNOWN_DRIVER_VALUE = "__unknown_driver__"

export function LogisticsDriverPicker({
  accessToken,
  disabled = false,
  id,
  required = true,
  unknownLabel,
  value,
  warehouseId,
  onChange,
}: {
  accessToken: string
  disabled?: boolean
  id: string
  required?: boolean
  unknownLabel?: string
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
    <Field data-invalid={(groupsQuery.isError && !unknownLabel) || undefined}>
      <FieldLabel htmlFor={id}>Водитель</FieldLabel>
      <Select
        value={value?.id ?? (unknownLabel ? UNKNOWN_DRIVER_VALUE : "")}
        disabled={
          disabled ||
          groupsQuery.isLoading ||
          (drivers.length === 0 && !unknownLabel)
        }
        onValueChange={(driverId) => {
          if (driverId === UNKNOWN_DRIVER_VALUE) {
            onChange(null)
            return
          }
          onChange(drivers.find((driver) => driver.id === driverId) ?? null)
        }}
      >
        <SelectTrigger id={id} aria-required={required || undefined}>
          <SelectValue placeholder="Выберите водителя" />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            {unknownLabel ? (
              <SelectItem value={UNKNOWN_DRIVER_VALUE}>
                {unknownLabel}
              </SelectItem>
            ) : null}
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
          {unknownLabel
            ? `В настройках склада нет активных водителей. Можно сохранить «${unknownLabel}».`
            : "В настройках выбранного склада нет активных водителей. Создайте водителя в настройках логистики этого склада."}
        </FieldDescription>
      ) : null}
      {groupsQuery.isError ? (
        unknownLabel ? (
          <FieldDescription>
            Справочник водителей недоступен. Можно сохранить «{unknownLabel}».
          </FieldDescription>
        ) : (
          <FieldError>
            {groupsQuery.error instanceof Error
              ? groupsQuery.error.message
              : "Не удалось загрузить справочник водителей."}
          </FieldError>
        )
      ) : null}
    </Field>
  )
}
