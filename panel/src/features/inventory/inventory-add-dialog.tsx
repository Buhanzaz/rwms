import { useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Field, FieldError, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  INVENTORY_QUERY_KEY,
  addInventoryRentalItem,
  createInventoryFindingId,
  inventoryDetailQueryKey,
  resolveInventoryNumber,
} from "@/features/inventory/api/inventory-api"
import type {
  InventoryActorSnapshot,
  InventoryFindingDto,
  InventorySessionDto,
} from "@/features/inventory/model/inventory"
import {
  RentalItemCreationDialog,
  type RentalItemCreationCommand,
} from "@/features/rental-items/rental-item-create-dialog"
import { ApiError } from "@/lib/api-client"

type CreateCondition = "NEW" | "USED"

function commandError(error: unknown) {
  if (error instanceof ApiError && error.status === 409) {
    return "Данные инвентаризации изменились. Обновите страницу и повторите действие."
  }
  return error instanceof Error ? error.message : null
}

export function InventoryAddDialog({
  open,
  session,
  actor,
  onOpenChange,
  onResolved,
}: {
  open: boolean
  session: InventorySessionDto
  actor: InventoryActorSnapshot
  onOpenChange: (open: boolean) => void
  onResolved: (
    session: InventorySessionDto,
    finding: InventoryFindingDto
  ) => void
}) {
  const queryClient = useQueryClient()
  const [number, setNumber] = useState("")
  const [notFoundNumber, setNotFoundNumber] = useState<string | null>(null)
  const [findingId, setFindingId] = useState<string | null>(null)
  const [notFoundSession, setNotFoundSession] =
    useState<InventorySessionDto | null>(null)
  const [createCondition, setCreateCondition] =
    useState<CreateCondition | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  function reset() {
    setNumber("")
    setNotFoundNumber(null)
    setFindingId(null)
    setNotFoundSession(null)
    setCreateCondition(null)
    setMessage(null)
  }

  const resolveMutation = useMutation({
    mutationFn: () =>
      resolveInventoryNumber({
        inventoryId: session.id,
        expectedVersion: session.version,
        actor,
        number,
      }),
    onSuccess: (resolution) => {
      if (
        resolution.kind === "EXISTING_FINDING" ||
        resolution.kind === "OPEN_INSPECTION"
      ) {
        queryClient.setQueryData(
          inventoryDetailQueryKey(resolution.session.id),
          resolution.session
        )
        reset()
        onOpenChange(false)
        onResolved(resolution.session, resolution.finding)
        return
      }
      if (resolution.kind === "NOT_FOUND") {
        queryClient.setQueryData(
          inventoryDetailQueryKey(resolution.session.id),
          resolution.session
        )
        setNotFoundNumber(resolution.canonicalNumber)
        setFindingId(createInventoryFindingId())
        setNotFoundSession(resolution.session)
        setMessage(null)
        return
      }
      if (resolution.kind === "CONFLICT") {
        void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
        setMessage(
          resolution.conflict === "RENTAL_ITEM_MISSING"
            ? `Бытовка ${resolution.finding.cabinNumber} отсутствует в актуальном реестре.`
            : resolution.conflict === "OTHER_WAREHOUSE"
              ? `Бытовка ${resolution.item?.number ?? resolution.finding.cabinNumber} относится к другому складу.`
              : `Бытовка ${resolution.item?.number ?? resolution.finding.cabinNumber} списана и не может быть восстановлена.`
        )
      }
    },
  })

  async function createAndAttachRentalItem(command: RentalItemCreationCommand) {
    if (!notFoundNumber || !findingId || !createCondition || !notFoundSession) {
      throw new Error("Сначала найдите номер бытовки и выберите её состояние.")
    }

    const result = await addInventoryRentalItem({
      inventoryId: notFoundSession.id,
      expectedVersion: notFoundSession.version,
      actor,
      findingId,
      condition: createCondition,
      rentalItem: {
        number: notFoundNumber,
        rentalTypeId: command.rentalTypeId,
        dimensionId: command.dimensionId,
        finishingId: command.finishingId,
        category: command.category,
        characteristicIds: command.characteristicIds,
        linoleum: command.linoleum,
      },
    })
    const snapshot = result.rentalItem ?? result.finding.currentSnapshot
    if (!snapshot || !snapshot.warehouseId) {
      throw new Error(
        "Бытовка добавлена в инвентаризацию, но сервис не вернул её идентификатор."
      )
    }
    const assetId =
      "assetId" in snapshot ? snapshot.assetId : snapshot.rentalItemId
    const assetNumber =
      "displayCanonicalNumber" in snapshot
        ? snapshot.displayCanonicalNumber
        : snapshot.number
    if (!assetId) {
      throw new Error(
        "Бытовка добавлена в инвентаризацию, но сервис не вернул её идентификатор."
      )
    }

    return {
      createdItem: {
        id: assetId,
        warehouseId: snapshot.warehouseId,
        number: assetNumber,
      },
      value: result,
    }
  }

  function completeInventoryAddition(result: {
    session: InventorySessionDto
    finding: InventoryFindingDto
  }) {
    queryClient.setQueryData(
      inventoryDetailQueryKey(result.session.id),
      result.session
    )
    void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
    onResolved(result.session, result.finding)
  }

  const pending = resolveMutation.isPending
  const error = commandError(resolveMutation.error) || message

  if (createCondition && notFoundNumber && findingId && notFoundSession) {
    const isNew = createCondition === "NEW"
    return (
      <RentalItemCreationDialog
        open={open}
        warehouseId={notFoundSession.warehouseId}
        onOpenChange={(nextOpen) => {
          if (!nextOpen) {
            reset()
            onOpenChange(false)
          }
        }}
        title={isNew ? "Создание новой бытовки" : "Создание б/у бытовки"}
        description={
          isNew
            ? "Бытовка будет добавлена как новая и сразу прикреплена к этой инвентаризации."
            : "Бытовка будет добавлена как б/у и сразу прикреплена к этой инвентаризации."
        }
        initialNumber={notFoundNumber}
        numberReadOnly
        categoryMode={isNew ? "NEW" : "USED"}
        photosEnabled={false}
        submitLabel="Создать и осмотреть"
        createRentalItem={createAndAttachRentalItem}
        errorMessage={commandError}
        onCompleted={completeInventoryAddition}
      />
    )
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen && !pending) reset()
        onOpenChange(nextOpen)
      }}
    >
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Добавить бытовку</DialogTitle>
          <DialogDescription>
            Введите точный номер. Поиск повторно проверит весь реестр.
          </DialogDescription>
        </DialogHeader>

        {notFoundNumber ? (
          <div className="flex flex-col gap-4">
            <p>
              Номер <strong>{notFoundNumber}</strong> не найден. Какую бытовку
              добавить?
            </p>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setCreateCondition("USED")}
              >
                Добавить б/у
              </Button>
              <Button type="button" onClick={() => setCreateCondition("NEW")}>
                Добавить новую
              </Button>
            </DialogFooter>
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            <Field>
              <FieldLabel htmlFor="inventory-number-search">
                Номер бытовки
              </FieldLabel>
              <Input
                id="inventory-number-search"
                autoFocus
                value={number}
                onChange={(event) => setNumber(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && number.trim()) {
                    resolveMutation.mutate()
                  }
                }}
              />
            </Field>
            {error ? <FieldError role="alert">{error}</FieldError> : null}
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                disabled={pending}
                onClick={() => {
                  reset()
                  onOpenChange(false)
                }}
              >
                Отмена
              </Button>
              <Button
                type="button"
                disabled={pending || !number.trim()}
                onClick={() => resolveMutation.mutate()}
              >
                {pending ? "Проверяем..." : "Найти"}
              </Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
