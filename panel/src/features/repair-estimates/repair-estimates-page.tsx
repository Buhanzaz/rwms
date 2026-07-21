import { useState } from "react"
import { useNavigate, useSearchParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { Add01Icon, ArrowLeft01Icon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import {
  getRepairEstimate,
  listRepairEstimates,
  repairEstimateDetailQueryKey,
  repairEstimateListQueryKey,
} from "@/features/repair-estimates/api/repair-estimates-api"
import type {
  RepairEstimateDto,
  RepairEstimateStatus,
  RepairEstimateSummaryDto,
} from "@/features/repair-estimates/model/repair-estimate"
import { RepairEstimateEditorWorkspace } from "@/features/repair-estimates/repair-estimate-editor-workspace"
import { useWarehouse } from "@/hooks/use-warehouse"
import {
  useWorkspaceBack,
  workspaceEntryNavigationOptions,
} from "@/hooks/use-workspace-back"

function formatDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium" }).format(
    new Date(value)
  )
}

function estimateStatusLabel(status: RepairEstimateStatus) {
  return status === "COMPLETED" ? "Завершена" : "Требует доработки"
}

export function RepairEstimatesPage() {
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const canEdit = Boolean(
    selectedWarehouseId &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "EDIT")
  )
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const estimateId = searchParams.get("estimateId")
  const createRequested = searchParams.get("create") === "1"
  const returnTaskId = searchParams.get("returnTaskId")
  const [selectedStatus, setSelectedStatus] =
    useState<RepairEstimateStatus>("DRAFT")
  const listSearchParams = new URLSearchParams(searchParams)
  listSearchParams.delete("estimateId")
  listSearchParams.delete("create")
  listSearchParams.delete("returnTaskId")
  const listSearch = listSearchParams.toString()
  const estimatesListHref = listSearch
    ? `/estimates?${listSearch}`
    : "/estimates"
  const goBack = useWorkspaceBack(estimatesListHref)

  const detailQuery = useQuery({
    queryKey: repairEstimateDetailQueryKey(
      selectedWarehouseId ?? "none",
      estimateId
    ),
    queryFn: () => getRepairEstimate(estimateId!, selectedWarehouseId!),
    enabled: Boolean(estimateId && selectedWarehouseId),
  })
  const effectiveStatus = detailQuery.data?.status ?? selectedStatus
  const listQuery = useQuery({
    queryKey: repairEstimateListQueryKey(
      selectedWarehouseId ?? "none",
      effectiveStatus
    ),
    queryFn: () => listRepairEstimates(selectedWarehouseId!, effectiveStatus),
    enabled: selectedWarehouseId !== null,
  })
  const unknownEstimateNotice =
    estimateId && detailQuery.isSuccess && detailQuery.data === null
      ? "Смета не найдена на выбранном складе"
      : null
  const detailLoadError =
    estimateId && detailQuery.isError ? "Не удалось загрузить смету." : null

  function openEstimate(id: string) {
    const next = new URLSearchParams(searchParams)
    next.delete("create")
    next.delete("returnTaskId")
    next.set("estimateId", id)
    navigate(`/estimates?${next.toString()}`, workspaceEntryNavigationOptions)
  }

  function clearEstimateSelection() {
    navigate(estimatesListHref, { replace: true })
  }

  function openCreateEditor() {
    const next = new URLSearchParams(searchParams)
    next.delete("estimateId")
    next.delete("returnTaskId")
    next.set("create", "1")
    navigate(`/estimates?${next.toString()}`, workspaceEntryNavigationOptions)
  }

  function closeEditor(nextStatus?: RepairEstimateStatus) {
    if (nextStatus) {
      setSelectedStatus(nextStatus)
    }
    clearEstimateSelection()
  }

  function handleSaved(estimate: RepairEstimateDto) {
    closeEditor(estimate.status)
  }

  const estimates = listQuery.data ?? []
  const workspaceOpen = createRequested || Boolean(estimateId)
  const detailUnavailable = Boolean(
    estimateId && !detailQuery.isLoading && !detailQuery.data
  )

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
        createRequested && !canEdit ? (
          <Card>
            <CardHeader>
              <CardTitle>Создание сметы недоступно</CardTitle>
              <CardDescription role="alert">
                Для создания сметы нужен доступ EDIT к выбранному складу.
              </CardDescription>
            </CardHeader>
          </Card>
        ) : returnTaskId ? (
          <Card>
            <CardHeader>
              <CardTitle>Смета из возврата пока недоступна</CardTitle>
              <CardDescription role="alert">
                Публичный logistics-контракт ещё не определяет серверную связь
                возврата со сметой. Создайте обычную смету без параметра
                returnTaskId.
              </CardDescription>
            </CardHeader>
          </Card>
        ) : detailUnavailable ? (
          <Card>
            <CardHeader>
              <CardTitle>Смета недоступна</CardTitle>
              <CardDescription role="alert">
                {detailLoadError ?? unknownEstimateNotice}
              </CardDescription>
            </CardHeader>
          </Card>
        ) : (
          <RepairEstimateEditorWorkspace
            accessToken={accessToken}
            warehouseId={selectedWarehouseId}
            estimate={estimateId ? (detailQuery.data ?? null) : null}
            readOnly={!canEdit}
            loading={Boolean(estimateId && detailQuery.isLoading)}
            onClose={() => closeEditor()}
            onSaved={handleSaved}
          />
        )
      ) : (
        <>
          <PageToolbar>
            <PageToolbarContent>
              <Tabs
                value={effectiveStatus}
                onValueChange={(value) =>
                  setSelectedStatus(value as RepairEstimateStatus)
                }
              >
                <TabsList className="w-full sm:w-fit">
                  <TabsTrigger value="DRAFT">Требуют доработки</TabsTrigger>
                  <TabsTrigger value="COMPLETED">Завершённые</TabsTrigger>
                </TabsList>
              </Tabs>
            </PageToolbarContent>

            {canEdit ? (
              <PageToolbarActions>
                <Button
                  type="button"
                  disabled={!selectedWarehouseId}
                  onClick={openCreateEditor}
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Создать смету
                </Button>
              </PageToolbarActions>
            ) : null}
          </PageToolbar>

          <div className="min-h-0 flex-1 overflow-auto md:flex">
            {listQuery.isLoading ? (
              <p className="text-xs text-muted-foreground">Загрузка смет...</p>
            ) : listQuery.isError ? (
              <p role="alert" className="text-xs text-destructive">
                Не удалось загрузить сметы.
              </p>
            ) : (
              <>
                <div className="hidden min-h-full flex-1 md:block">
                  <OperationsListGrid
                    className="min-h-full"
                    items={estimates}
                    columns={[
                      {
                        id: "cabinNumber",
                        label: "Номер бытовки",
                        className: "w-52",
                        getSortValue: (estimate) => estimate.cabinNumber,
                        render: (estimate) => (
                          <Button
                            type="button"
                            variant="link"
                            size="sm"
                            aria-label={`Открыть смету для бытовки ${estimate.cabinNumber}`}
                            onClick={() => openEstimate(estimate.id)}
                          >
                            {estimate.cabinNumber}
                          </Button>
                        ),
                      },
                      {
                        id: "sourceParty",
                        label: "От кого",
                        className: "w-52",
                        getSortValue: (estimate) => estimate.sourceParty,
                        render: (estimate) => estimate.sourceParty || "—",
                      },
                      {
                        id: "authorName",
                        label: "Автор",
                        className: "w-48",
                        getSortValue: (estimate) => estimate.authorName,
                        render: (estimate) => estimate.authorName,
                      },
                      {
                        id: "status",
                        label: "Статус",
                        className: "w-48",
                        getSortValue: (estimate) =>
                          estimateStatusLabel(estimate.status),
                        render: (estimate) => (
                          <Badge
                            variant={
                              estimate.status === "COMPLETED"
                                ? "secondary"
                                : "outline"
                            }
                          >
                            {estimateStatusLabel(estimate.status)}
                          </Badge>
                        ),
                      },
                      {
                        id: "createdAt",
                        label: "Создана",
                        className: "w-44",
                        getSortValue: (estimate) =>
                          new Date(estimate.createdAt).getTime(),
                        render: (estimate) => formatDate(estimate.createdAt),
                      },
                    ]}
                  />
                </div>

                {estimates.length > 0 ? (
                  <div className="grid gap-3 md:hidden">
                    {estimates.map((estimate) => (
                      <EstimateMobileCard
                        key={estimate.id}
                        estimate={estimate}
                        onOpen={() => openEstimate(estimate.id)}
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

function EstimateMobileCard({
  estimate,
  onOpen,
}: {
  estimate: RepairEstimateSummaryDto
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
            aria-label={`Открыть смету для бытовки ${estimate.cabinNumber}`}
            onClick={onOpen}
          >
            {estimate.cabinNumber}
          </Button>
        </CardTitle>
      </CardHeader>
      <CardContent className="grid grid-cols-2 gap-2">
        <span className="text-muted-foreground">От кого</span>
        <span>{estimate.sourceParty || "—"}</span>
        <span className="text-muted-foreground">Автор</span>
        <span>{estimate.authorName}</span>
        <span className="text-muted-foreground">Статус</span>
        <span>
          <Badge
            variant={estimate.status === "COMPLETED" ? "secondary" : "outline"}
          >
            {estimateStatusLabel(estimate.status)}
          </Badge>
        </span>
        <span className="text-muted-foreground">Создана</span>
        <span>{formatDate(estimate.createdAt)}</span>
      </CardContent>
    </Card>
  )
}
