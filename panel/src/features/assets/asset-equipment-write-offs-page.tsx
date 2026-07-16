import { useMemo, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import {
  disposeAssetEquipment,
  listAssetEquipment,
  listAssetEquipmentDispositions,
  type AssetEquipmentBalance,
} from "@/api/asset-api"
import { ASSET_EQUIPMENT_QUERY_KEY } from "@/features/assets/asset-query-keys"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"
import { useWarehouse } from "@/hooks/use-warehouse"

const DISPOSITIONS_QUERY_KEY = ["asset-equipment-dispositions"] as const

function dispositionsQueryKey(warehouseId: string) {
  return [...DISPOSITIONS_QUERY_KEY, warehouseId] as const
}

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Не удалось выполнить списание."
}

function canManageWarehouse(
  currentUser: ReturnType<typeof useAuth>["currentUser"],
  warehouseId: string
) {
  if (currentUser === null) return false
  if (isGlobalAdministrator(currentUser.globalRole)) return true
  if (currentUser.warehouseAccessAll) return currentUser.globalRole !== "VIEWER"
  return currentUser.warehouseAccesses.some(
    (grant) => grant.warehouseId === warehouseId && grant.level === "MANAGE"
  )
}

type SourceChoice = {
  equipmentId: string
  equipmentCode: string
  equipmentName: string
  balance: AssetEquipmentBalance
}

function sourceLabel(choice: SourceChoice) {
  return `${choice.equipmentCode} · ${choice.equipmentName} · ${choice.balance.locationKind} · ${choice.balance.quantity} шт.`
}

function DispositionDialog({
  sources,
  pending,
  onClose,
  onSave,
}: {
  sources: SourceChoice[]
  pending: boolean
  onClose: () => void
  onSave: (
    choice: SourceChoice,
    quantity: number,
    disposition: "WRITE_OFF" | "LOSS"
  ) => Promise<void>
}) {
  const [sourceId, setSourceId] = useState(sources[0]?.balance.id ?? "")
  const [quantity, setQuantity] = useState("1")
  const [disposition, setDisposition] = useState<"WRITE_OFF" | "LOSS">(
    "WRITE_OFF"
  )
  const [error, setError] = useState<string | null>(null)
  const selected =
    sources.find((source) => source.balance.id === sourceId) ?? null

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const parsedQuantity = Number(quantity)
    if (
      selected === null ||
      !Number.isInteger(parsedQuantity) ||
      parsedQuantity < 1 ||
      parsedQuantity > selected.balance.quantity
    ) {
      setError("Выберите остаток и укажите целое количество в его пределах.")
      return
    }
    setError(null)
    await onSave(selected, parsedQuantity, disposition)
  }

  return (
    <Dialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Списание оборудования</DialogTitle>
          <DialogDescription>
            Команда уменьшает исходный остаток и фиксирует неизменяемое движение
            в ledger.
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(event) => void submit(event)}
          className="flex flex-col gap-6"
        >
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="asset-disposition-source">
                Источник
              </FieldLabel>
              <Select value={sourceId} onValueChange={setSourceId}>
                <SelectTrigger id="asset-disposition-source">
                  <SelectValue placeholder="Выберите остаток" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {sources.map((source) => (
                      <SelectItem
                        key={source.balance.id}
                        value={source.balance.id}
                      >
                        {sourceLabel(source)}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-disposition-quantity">
                Количество
              </FieldLabel>
              <Input
                id="asset-disposition-quantity"
                type="number"
                min={1}
                max={selected?.balance.quantity ?? 1}
                step={1}
                value={quantity}
                onChange={(event) => setQuantity(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="asset-disposition-kind">Решение</FieldLabel>
              <Select
                value={disposition}
                onValueChange={(value) =>
                  setDisposition(value as "WRITE_OFF" | "LOSS")
                }
              >
                <SelectTrigger id="asset-disposition-kind">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value="WRITE_OFF">Списать</SelectItem>
                    <SelectItem value="LOSS">Утрата</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
          </FieldGroup>
          {error ? <FieldError>{error}</FieldError> : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={onClose}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? "Сохраняем…" : "Подтвердить"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

export function AssetEquipmentWriteOffsPage() {
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouseId } = useWarehouse()
  const queryClient = useQueryClient()
  const [dialogOpen, setDialogOpen] = useState(false)
  const canManage =
    selectedWarehouseId !== null &&
    canManageWarehouse(currentUser, selectedWarehouseId)
  const dispositionsQuery = useQuery({
    queryKey: dispositionsQueryKey(selectedWarehouseId ?? "none"),
    queryFn: () =>
      listAssetEquipmentDispositions(accessToken, selectedWarehouseId!),
    enabled: accessToken !== null && selectedWarehouseId !== null,
  })
  const equipmentQuery = useQuery({
    queryKey: [...ASSET_EQUIPMENT_QUERY_KEY, selectedWarehouseId ?? "none"],
    queryFn: () => listAssetEquipment(accessToken, selectedWarehouseId!),
    enabled: accessToken !== null && selectedWarehouseId !== null && dialogOpen,
  })
  const sources = useMemo<SourceChoice[]>(
    () =>
      (equipmentQuery.data ?? []).flatMap(({ equipment, totals }) =>
        totals.balances
          .filter(
            (balance) =>
              balance.quantity > 0 &&
              balance.locationKind !== "WRITTEN_OFF" &&
              balance.locationKind !== "LOST"
          )
          .map((balance) => ({
            equipmentId: equipment.id,
            equipmentCode: equipment.code,
            equipmentName: equipment.name,
            balance,
          }))
      ),
    [equipmentQuery.data]
  )
  const dispositionMutation = useMutation({
    mutationFn: async ({
      choice,
      quantity,
      disposition,
    }: {
      choice: SourceChoice
      quantity: number
      disposition: "WRITE_OFF" | "LOSS"
    }) => {
      if (selectedWarehouseId === null) throw new Error("Выберите склад.")
      return disposeAssetEquipment(accessToken, crypto.randomUUID(), {
        equipmentId: choice.equipmentId,
        warehouseId: selectedWarehouseId,
        sourceRentalItemId: choice.balance.rentalItemId,
        sourceLocationKind: choice.balance.locationKind,
        sourceExpectedVersion: choice.balance.version,
        quantity,
        disposition,
      })
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: DISPOSITIONS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: ASSET_EQUIPMENT_QUERY_KEY }),
      ])
      setDialogOpen(false)
      toast.success("Движение оборудования зафиксировано.")
    },
    onError: (error) => toast.error(errorMessage(error)),
  })
  const dispositions = dispositionsQuery.data ?? []

  return (
    <div className="min-h-0 overflow-y-auto pb-6">
      <div className="flex flex-col gap-4">
        <Card size="sm">
          <CardHeader>
            <CardTitle>Списание дополнительного оборудования</CardTitle>
            <CardDescription>
              История берётся из asset-service и не зависит от return/repair
              browser workflow.
            </CardDescription>
          </CardHeader>
          {canManage ? (
            <CardFooter className="justify-end">
              <Button onClick={() => setDialogOpen(true)}>
                Новое списание
              </Button>
            </CardFooter>
          ) : null}
        </Card>
        {dispositionsQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Загрузка истории…</p>
        ) : dispositionsQuery.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {errorMessage(dispositionsQuery.error)}
          </p>
        ) : dispositions.length === 0 ? (
          <Card>
            <CardHeader>
              <CardTitle>Списаний пока нет</CardTitle>
              <CardDescription>
                Новые списания создаются только пользователем с правом MANAGE.
              </CardDescription>
            </CardHeader>
          </Card>
        ) : (
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {dispositions.map((entry) => (
              <Card key={entry.movement.id}>
                <CardHeader>
                  <CardTitle>{entry.equipmentName}</CardTitle>
                  <CardDescription>{entry.equipmentCode}</CardDescription>
                </CardHeader>
                <CardContent className="flex flex-col gap-1 text-sm">
                  <p>
                    {entry.movement.kind === "EQUIPMENT_LOST"
                      ? "Утрата"
                      : "Списание"}
                    : {entry.movement.quantity} шт.
                  </p>
                  <p className="text-muted-foreground">
                    {new Intl.DateTimeFormat("ru-RU", {
                      dateStyle: "medium",
                      timeStyle: "short",
                    }).format(new Date(entry.movement.occurredAt))}
                  </p>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </div>
      {dialogOpen ? (
        <DispositionDialog
          sources={sources}
          pending={dispositionMutation.isPending}
          onClose={() => setDialogOpen(false)}
          onSave={async (choice, quantity, disposition) => {
            await dispositionMutation.mutateAsync({
              choice,
              quantity,
              disposition,
            })
          }}
        />
      ) : null}
    </div>
  )
}
