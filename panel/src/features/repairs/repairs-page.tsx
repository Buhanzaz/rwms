import { useCallback } from "react"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Navigate,
  useLocation,
  useNavigate,
  useSearchParams,
} from "react-router-dom"

import { MobileAppRequiredDialog } from "@/components/mobile-app-required-dialog"
import { PageToolbar } from "@/components/page-toolbar"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  getRepairTask,
  repairTaskDetailQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import { canEditRepairTaskPlan } from "@/features/repair-tasks/domain/repair-task-domain"
import type {
  RentalItemRepairSeed,
  RepairsLocationState,
} from "@/features/repair-tasks/model/repair-task"
import { RepairTaskDetailWorkspace } from "@/features/repair-tasks/repair-task-detail-workspace"
import { RepairTaskEditorWorkspace } from "@/features/repair-tasks/repair-task-editor-workspace"
import { canInitiatePropertyDisposition } from "@/features/write-offs/property-disposition-presentation"
import { useIsMobile } from "@/hooks/use-mobile"
import { useWarehouse } from "@/hooks/use-warehouse"
import { useWorkspaceBack } from "@/hooks/use-workspace-back"

function resolveRentalItemRepairSeed(
  value: unknown,
  selectedWarehouseId: string | null
): RentalItemRepairSeed | undefined {
  if (
    !selectedWarehouseId ||
    !value ||
    typeof value !== "object" ||
    !("type" in value) ||
    value.type !== "rental-item-repair-seed-v1" ||
    !("warehouseId" in value) ||
    value.warehouseId !== selectedWarehouseId ||
    !("rentalItemId" in value) ||
    typeof value.rentalItemId !== "string" ||
    !value.rentalItemId ||
    !("number" in value) ||
    typeof value.number !== "string" ||
    !value.number
  ) {
    return undefined
  }

  return value as RentalItemRepairSeed
}

function RepairsWorkspace({
  repairId,
  createRequested,
  editRequested,
}: {
  repairId: string | null
  createRequested: boolean
  editRequested: boolean
}) {
  const isMobile = useIsMobile()
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const canEdit = Boolean(
    selectedWarehouseId &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "EDIT")
  )
  const canManage = Boolean(
    selectedWarehouseId &&
    canInitiatePropertyDisposition(currentUser, selectedWarehouseId)
  )
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const workspaceBack = useWorkspaceBack("/task-board")
  const locationState = location.state as RepairsLocationState | null
  const goBack = useCallback(() => {
    if (locationState?.workspaceEntry === true) {
      navigate(-1)
      return
    }
    workspaceBack()
  }, [locationState?.workspaceEntry, navigate, workspaceBack])
  const reworkSeed = createRequested ? locationState?.reworkSeed : undefined
  const rentalItemSeed = createRequested
    ? resolveRentalItemRepairSeed(
        locationState?.rentalItemSeed,
        selectedWarehouseId
      )
    : undefined

  const detailQuery = useQuery({
    queryKey: repairTaskDetailQueryKey(selectedWarehouseId ?? "none", repairId),
    queryFn: () => getRepairTask(repairId!, selectedWarehouseId!),
    enabled: Boolean(repairId && selectedWarehouseId),
  })
  const reworkSourceQuery = useQuery({
    queryKey: repairTaskDetailQueryKey(
      selectedWarehouseId ?? "none",
      reworkSeed?.sourceRepairTaskId ?? null
    ),
    queryFn: () =>
      getRepairTask(reworkSeed!.sourceRepairTaskId, selectedWarehouseId!),
    enabled: Boolean(reworkSeed && selectedWarehouseId),
  })

  const selectedRepair = detailQuery.data ?? null
  const selectedRepairPlanEditable = Boolean(
    selectedRepair && canEditRepairTaskPlan(selectedRepair)
  )
  const showRepairEditor = Boolean(
    selectedRepair &&
    (selectedRepair.status === "DRAFT" ||
      (editRequested && canEdit && selectedRepairPlanEditable))
  )

  function editRepair() {
    if (!selectedRepair) return
    const next = new URLSearchParams(searchParams)
    next.delete("create")
    next.set("repairId", selectedRepair.id)
    next.set("edit", "1")
    navigate(`/repairs?${next.toString()}`, {
      replace: true,
      state: location.state,
    })
  }

  const workspaceBackToolbar = (
    <PageToolbar>
      <Button type="button" variant="outline" onClick={goBack}>
        <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
        Назад
      </Button>
    </PageToolbar>
  )

  if (isMobile && createRequested) {
    return (
      <MobileAppRequiredDialog
        open
        onOpenChange={(open) => {
          if (!open) goBack()
        }}
        operation="Создание ремонта"
      />
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      {!selectedWarehouseId ? workspaceBackToolbar : null}

      {selectedWarehouseId ? (
        createRequested && !canEdit ? (
          <>
            {workspaceBackToolbar}
            <Card>
              <CardHeader>
                <CardTitle>Создание ремонта недоступно</CardTitle>
                <CardDescription role="alert">
                  Для создания ремонта нужен доступ EDIT к выбранному складу.
                </CardDescription>
              </CardHeader>
            </Card>
          </>
        ) : repairId && !detailQuery.isLoading && !selectedRepair ? (
          <>
            {workspaceBackToolbar}
            <Card>
              <CardHeader>
                <CardTitle>Ремонт недоступен</CardTitle>
                <CardDescription role="alert">
                  {detailQuery.isError
                    ? "Не удалось загрузить ремонт."
                    : "Ремонт не найден на выбранном складе."}
                </CardDescription>
              </CardHeader>
            </Card>
          </>
        ) : reworkSeed &&
          !reworkSourceQuery.isLoading &&
          (!reworkSourceQuery.data ||
            reworkSeed.warehouseId !== selectedWarehouseId ||
            reworkSourceQuery.data.version !==
              reworkSeed.sourceRepairTaskVersion ||
            reworkSourceQuery.data.rentalItemId !== reworkSeed.rentalItemId ||
            reworkSourceQuery.data.status !== "COMPLETED" ||
            reworkSourceQuery.data.acceptanceStatus !== "PENDING") ? (
          <>
            {workspaceBackToolbar}
            <Card>
              <CardHeader>
                <CardTitle>Доработка недоступна</CardTitle>
                <CardDescription role="alert">
                  Исходное задание изменилось или больше не ожидает приёмки.
                </CardDescription>
              </CardHeader>
            </Card>
          </>
        ) : selectedRepair && !showRepairEditor ? (
          <>
            {workspaceBackToolbar}
            <RepairTaskDetailWorkspace
              accessToken={accessToken}
              task={selectedRepair}
              readOnly={!canEdit}
              onEdit={
                canEdit && selectedRepairPlanEditable ? editRepair : undefined
              }
            />
          </>
        ) : (
          <RepairTaskEditorWorkspace
            accessToken={accessToken}
            warehouseId={selectedWarehouseId}
            task={selectedRepair}
            readOnly={!canEdit}
            canManage={canManage}
            sourceTask={reworkSourceQuery.data}
            seed={reworkSourceQuery.data ? reworkSeed : undefined}
            initialRentalItemId={
              repairId || reworkSeed ? undefined : rentalItemSeed?.rentalItemId
            }
            loading={Boolean(
              (repairId && detailQuery.isLoading) ||
              (reworkSeed && reworkSourceQuery.isLoading)
            )}
            onBack={goBack}
            onClose={goBack}
            onSaved={goBack}
          />
        )
      ) : null}
    </div>
  )
}

export function RepairsPage() {
  const [searchParams] = useSearchParams()
  const repairId = searchParams.get("repairId")?.trim() || null
  const createRequested = !repairId && searchParams.get("create") === "1"

  if (!repairId && !createRequested) {
    return <Navigate to="/task-board" replace />
  }

  return (
    <RepairsWorkspace
      repairId={repairId}
      createRequested={createRequested}
      editRequested={Boolean(repairId && searchParams.get("edit") === "1")}
    />
  )
}
