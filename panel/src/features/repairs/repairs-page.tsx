import { useLocation, useNavigate, useSearchParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { Add01Icon, ArrowLeft01Icon } from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { OperationsListGrid } from "@/components/operations-list-grid"
import { PageToolbar, PageToolbarActions } from "@/components/page-toolbar"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  getRepairTask,
  listRepairTasks,
  repairTaskDetailQueryKey,
  repairTasksListQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import type {
  RepairTaskDto,
  RepairsLocationState,
} from "@/features/repair-tasks/model/repair-task"
import type { RentalItemDossierNavigationState } from "@/features/rental-items/dossier/model/rental-item-dossier"
import { RepairTaskDetailWorkspace } from "@/features/repair-tasks/repair-task-detail-workspace"
import { RepairTaskEditorWorkspace } from "@/features/repair-tasks/repair-task-editor-workspace"
import { RepairTaskStatusBadge } from "@/features/repair-tasks/repair-task-status-badge"
import { useWarehouse } from "@/hooks/use-warehouse"
import {
  useWorkspaceBack,
  workspaceEntryNavigationOptions,
} from "@/hooks/use-workspace-back"

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function RepairMobileCard({
  repair,
  onOpen,
}: {
  repair: RepairTaskDto
  onOpen: () => void
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <Button
            type="button"
            variant="link"
            size="sm"
            aria-label={`Открыть ремонт бытовки ${repair.cabinNumber}`}
            onClick={onOpen}
          >
            {repair.cabinNumber}
          </Button>
        </CardTitle>
      </CardHeader>
      <CardContent className="grid grid-cols-2 gap-2">
        <span className="text-muted-foreground">Источник</span>
        <span>{repair.sourceParty || "—"}</span>
        <span className="text-muted-foreground">Идентификатор автора</span>
        <span>{repair.actorId || "—"}</span>
        <span className="text-muted-foreground">Статус</span>
        <span>
          <RepairTaskStatusBadge status={repair.status} />
        </span>
        <span className="text-muted-foreground">Создана</span>
        <span>{formatDate(repair.createdAt)}</span>
      </CardContent>
    </Card>
  )
}

export function RepairsPage() {
  const { selectedWarehouseId } = useWarehouse()
  const { currentUser } = useAuth()
  const canEdit = Boolean(
    selectedWarehouseId &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "EDIT")
  )
  const canManage = Boolean(
    selectedWarehouseId &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "MANAGE")
  )
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const repairId = searchParams.get("repairId")
  const createRequested = searchParams.get("create") === "1"
  const locationState = location.state as
    (RepairsLocationState & RentalItemDossierNavigationState) | null
  const reworkSeed = createRequested ? locationState?.reworkSeed : undefined
  const rentalItemSeed =
    createRequested &&
    locationState?.rentalItemSeed?.type === "rental-item-repair-seed-v1" &&
    locationState.rentalItemSeed.warehouseId === selectedWarehouseId
      ? locationState.rentalItemSeed
      : undefined

  const listSearchParams = new URLSearchParams(searchParams)
  listSearchParams.delete("repairId")
  listSearchParams.delete("create")
  const listSearch = listSearchParams.toString()
  const repairsListHref = listSearch ? `/repairs?${listSearch}` : "/repairs"
  const goBack = useWorkspaceBack(repairsListHref)
  const listQuery = useQuery({
    queryKey: repairTasksListQueryKey(selectedWarehouseId ?? "none"),
    queryFn: () => listRepairTasks(selectedWarehouseId!),
    enabled: selectedWarehouseId !== null,
  })
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

  const repairs = listQuery.data ?? []
  const selectedRepair = detailQuery.data ?? null
  const workspaceOpen = createRequested || Boolean(repairId)

  function closeWorkspace() {
    navigate(repairsListHref, { replace: true })
  }

  function handleSaved() {
    closeWorkspace()
  }

  function openRepair(repairId: string) {
    const next = new URLSearchParams(searchParams)
    next.delete("create")
    next.set("repairId", repairId)
    navigate(`/repairs?${next.toString()}`, workspaceEntryNavigationOptions)
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      {workspaceOpen ? (
        <header className="flex flex-wrap items-center gap-3">
          <Button type="button" variant="outline" onClick={goBack}>
            <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
            Назад
          </Button>
        </header>
      ) : null}

      {workspaceOpen && selectedWarehouseId ? (
        createRequested && !repairId && !canEdit ? (
          <Card>
            <CardHeader>
              <CardTitle>Создание ремонта недоступно</CardTitle>
              <CardDescription role="alert">
                Для создания ремонта нужен доступ EDIT к выбранному складу.
              </CardDescription>
            </CardHeader>
          </Card>
        ) : repairId && !detailQuery.isLoading && !selectedRepair ? (
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
        ) : reworkSeed &&
          !reworkSourceQuery.isLoading &&
          (!reworkSourceQuery.data ||
            reworkSeed.warehouseId !== selectedWarehouseId ||
            reworkSourceQuery.data.version !==
              reworkSeed.sourceRepairTaskVersion ||
            reworkSourceQuery.data.rentalItemId !== reworkSeed.rentalItemId ||
            reworkSourceQuery.data.status !== "COMPLETED" ||
            reworkSourceQuery.data.acceptanceStatus !== "PENDING") ? (
          <Card>
            <CardHeader>
              <CardTitle>Доработка недоступна</CardTitle>
              <CardDescription role="alert">
                Исходное задание изменилось или больше не ожидает приёмки.
              </CardDescription>
            </CardHeader>
          </Card>
        ) : selectedRepair && selectedRepair.status !== "DRAFT" ? (
          <RepairTaskDetailWorkspace
            task={selectedRepair}
            readOnly={!canEdit}
          />
        ) : (
          <RepairTaskEditorWorkspace
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
            onClose={closeWorkspace}
            onSaved={handleSaved}
          />
        )
      ) : (
        <>
          {canEdit ? (
            <PageToolbar>
              <PageToolbarActions>
                <Button
                  type="button"
                  disabled={!selectedWarehouseId}
                  onClick={() => {
                    const next = new URLSearchParams(searchParams)
                    next.delete("repairId")
                    next.set("create", "1")
                    navigate(
                      `/repairs?${next.toString()}`,
                      workspaceEntryNavigationOptions
                    )
                  }}
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Создать задание
                </Button>
              </PageToolbarActions>
            </PageToolbar>
          ) : null}

          <div className="min-h-0 flex-1 overflow-y-auto md:flex">
            {listQuery.isLoading ? (
              <p className="text-xs text-muted-foreground">
                Загрузка ремонтов...
              </p>
            ) : listQuery.isError ? (
              <p role="alert" className="text-xs text-destructive">
                Не удалось загрузить ремонты.
              </p>
            ) : (
              <>
                <div className="hidden min-h-full flex-1 md:block">
                  <OperationsListGrid
                    className="min-h-full"
                    items={repairs}
                    columns={[
                      {
                        id: "cabinNumber",
                        label: "Номер бытовки",
                        className: "w-52",
                        getSortValue: (repair) => repair.cabinNumber,
                        render: (repair) => (
                          <Button
                            type="button"
                            variant="link"
                            size="sm"
                            aria-label={`Открыть ремонт бытовки ${repair.cabinNumber}`}
                            onClick={() => openRepair(repair.id)}
                          >
                            {repair.cabinNumber}
                          </Button>
                        ),
                      },
                      {
                        id: "sourceParty",
                        label: "Источник",
                        className: "w-52",
                        getSortValue: (repair) => repair.sourceParty,
                        render: (repair) => repair.sourceParty || "—",
                      },
                      {
                        id: "actorId",
                        label: "Идентификатор автора",
                        className: "w-48",
                        getSortValue: (repair) => repair.actorId,
                        render: (repair) => repair.actorId || "—",
                      },
                      {
                        id: "status",
                        label: "Статус",
                        className: "w-48",
                        getSortValue: (repair) => repair.status,
                        render: (repair) => (
                          <RepairTaskStatusBadge status={repair.status} />
                        ),
                      },
                      {
                        id: "createdAt",
                        label: "Создана",
                        className: "w-44",
                        getSortValue: (repair) =>
                          new Date(repair.createdAt).getTime(),
                        render: (repair) => formatDate(repair.createdAt),
                      },
                    ]}
                  />
                </div>

                {repairs.length > 0 ? (
                  <div className="grid gap-3 md:hidden">
                    {repairs.map((repair) => (
                      <RepairMobileCard
                        key={repair.id}
                        repair={repair}
                        onOpen={() => openRepair(repair.id)}
                      />
                    ))}
                  </div>
                ) : null}
              </>
            )}
          </div>
        </>
      )}
    </div>
  )
}
