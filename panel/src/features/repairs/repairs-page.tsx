import { useEffect } from "react"
import { useLocation, useNavigate, useSearchParams } from "react-router-dom"
import { useQuery, useQueryClient } from "@tanstack/react-query"
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
import {
  REPAIR_TASKS_QUERY_KEY,
  getRepairTask,
  listRepairTasks,
  repairTaskDetailQueryKey,
  repairTasksListQueryKey,
} from "@/features/repair-tasks/api/repair-tasks-api"
import {
  REPAIR_TASKS_MOCK_STORAGE_KEY,
  REPAIR_TASKS_UPDATED_EVENT,
} from "@/features/repair-tasks/adapters/local-storage-repair-tasks-adapter"
import { DEV_MAINTENANCE_FIXTURES_ENABLED } from "@/features/maintenance/maintenance-runtime"
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
        <span className="text-muted-foreground">Причина</span>
        <span>{repair.reason || "—"}</span>
        <span className="text-muted-foreground">Автор</span>
        <span>{repair.authorName}</span>
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
  const queryClient = useQueryClient()
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

  useEffect(() => {
    if (!DEV_MAINTENANCE_FIXTURES_ENABLED) return
    const invalidate = () => {
      void queryClient.invalidateQueries({ queryKey: REPAIR_TASKS_QUERY_KEY })
    }
    const handleStorage = (event: StorageEvent) => {
      if (event.key === REPAIR_TASKS_MOCK_STORAGE_KEY) {
        invalidate()
      }
    }
    window.addEventListener(REPAIR_TASKS_UPDATED_EVENT, invalidate)
    window.addEventListener("storage", handleStorage)
    return () => {
      window.removeEventListener(REPAIR_TASKS_UPDATED_EVENT, invalidate)
      window.removeEventListener("storage", handleStorage)
    }
  }, [queryClient])

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
        repairId && !detailQuery.isLoading && !selectedRepair ? (
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
          <RepairTaskDetailWorkspace task={selectedRepair} />
        ) : (
          <RepairTaskEditorWorkspace
            warehouseId={selectedWarehouseId}
            task={selectedRepair}
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
                        id: "reason",
                        label: "Причина",
                        className: "w-52",
                        getSortValue: (repair) => repair.reason,
                        render: (repair) => repair.reason || "—",
                      },
                      {
                        id: "authorName",
                        label: "Автор",
                        className: "w-48",
                        getSortValue: (repair) => repair.authorName,
                        render: (repair) => repair.authorName,
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
