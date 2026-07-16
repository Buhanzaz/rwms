import { useRef, useState } from "react"
import { useLocation, useNavigate, useSearchParams } from "react-router-dom"
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
  LogisticsEstimateSeed,
} from "@/features/repair-estimates/model/repair-estimate"
import type { RentalItemDossierNavigationState } from "@/features/rental-items/dossier/model/rental-item-dossier"
import { RepairEstimateEditorWorkspace } from "@/features/repair-estimates/repair-estimate-editor-workspace"
import { useWarehouse } from "@/hooks/use-warehouse"
import {
  useWorkspaceBack,
  workspaceEntryNavigationOptions,
} from "@/hooks/use-workspace-back"
import {
  cloneReturnMediaToPendingUploads,
  claimReturnForEstimate,
  createReturnReplacementEstimateLines,
  getReturnTask,
  markReturnEstimateCreated,
  recordReturnEstimateLinkFailure,
  releaseReturnEstimateClaim,
} from "@/features/logistics/api/logistics-api"

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
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const estimateId = searchParams.get("estimateId")
  const createRequested = searchParams.get("create") === "1"
  const dossierNavigationState = location.state as
    | (RentalItemDossierNavigationState & {
        estimateLinkageError?: string
      })
    | null
  const rentalItemSeed =
    createRequested &&
    dossierNavigationState?.rentalItemSeed?.type ===
      "rental-item-estimate-seed-v1" &&
    dossierNavigationState.rentalItemSeed.warehouseId === selectedWarehouseId
      ? dossierNavigationState.rentalItemSeed
      : undefined
  const returnTaskId = searchParams.get("returnTaskId")
  const [selectedStatus, setSelectedStatus] =
    useState<RepairEstimateStatus>("DRAFT")
  const logisticsSeedQuery = useQuery({
    queryKey: ["logistics", "estimate-seed", selectedWarehouseId, returnTaskId],
    queryFn: async (): Promise<LogisticsEstimateSeed | null> => {
      const task = await getReturnTask(selectedWarehouseId!, returnTaskId!)
      if (
        !task ||
        task.status !== "PENDING_INSPECTION" ||
        task.sourceEstimateId ||
        task.pendingEstimateId ||
        task.media.length === 0
      )
        return null
      const replacements = createReturnReplacementEstimateLines(task)
      return {
        type: "logistics-return-estimate-seed-v1",
        returnTaskId: task.id,
        returnTaskVersion: task.version,
        rentalItemId: task.rentalItemId,
        cabinNumber: task.cabinNumber,
        sourceParty: task.fromParty,
        dispatchDate: task.returnDate,
        media: [],
        pendingUploads: await cloneReturnMediaToPendingUploads(task.media),
        sourceMediaIds: task.media.map((item) => item.id),
        replacementLines: replacements.lines,
        replacementWarnings: replacements.warnings,
      }
    },
    enabled: Boolean(selectedWarehouseId && returnTaskId && createRequested),
    staleTime: Number.POSITIVE_INFINITY,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  const verifiedLogisticsSeed = logisticsSeedQuery.data ?? undefined
  const estimateClaimRef = useRef<{
    claimId: string
    version: number
  } | null>(null)

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

  async function handleSaved(estimate: RepairEstimateDto) {
    if (verifiedLogisticsSeed) {
      const claim = estimateClaimRef.current
      if (!claim) {
        throw new Error("Claim возврата отсутствует перед связыванием сметы")
      }
      try {
        await markReturnEstimateCreated({
          warehouseId: selectedWarehouseId!,
          returnTaskId: verifiedLogisticsSeed.returnTaskId,
          expectedVersion: claim.version,
          estimateId: estimate.id,
          estimateStatus: estimate.status,
          rentalItemId: verifiedLogisticsSeed.rentalItemId,
          claimId: claim.claimId,
        })
        estimateClaimRef.current = null
      } catch (cause) {
        try {
          await recordReturnEstimateLinkFailure({
            warehouseId: selectedWarehouseId!,
            returnTaskId: verifiedLogisticsSeed.returnTaskId,
            rentalItemId: verifiedLogisticsSeed.rentalItemId,
            estimateId: estimate.id,
            expectedVersion: claim.version,
            claimId: claim.claimId,
          })
        } catch {
          // The estimate remains saved and is opened below; recovery is visible.
        }
        estimateClaimRef.current = null
        navigate(`/estimates?estimateId=${encodeURIComponent(estimate.id)}`, {
          replace: true,
          state: {
            estimateLinkageError:
              cause instanceof Error
                ? `Смета сохранена, но связь с возвратом не создана: ${cause.message}`
                : "Смета сохранена, но связь с возвратом не создана",
          },
        })
        return
      }
    }
    closeEditor(estimate.status)
  }

  async function claimReturnBeforePersist() {
    if (!verifiedLogisticsSeed) return
    const current = await getReturnTask(
      selectedWarehouseId!,
      verifiedLogisticsSeed.returnTaskId
    )
    if (
      !current ||
      current.status !== "PENDING_INSPECTION" ||
      current.rentalItemId !== verifiedLogisticsSeed.rentalItemId ||
      current.cabinNumber !== verifiedLogisticsSeed.cabinNumber ||
      current.fromParty !== verifiedLogisticsSeed.sourceParty ||
      current.returnDate !== verifiedLogisticsSeed.dispatchDate ||
      current.version !== verifiedLogisticsSeed.returnTaskVersion ||
      current.sourceEstimateId ||
      current.pendingEstimateId ||
      current.media.map((item) => item.id).join("|") !==
        verifiedLogisticsSeed.sourceMediaIds.join("|")
    ) {
      throw new Error("Возврат изменился. Откройте создание сметы заново")
    }
    const claimed = await claimReturnForEstimate({
      warehouseId: selectedWarehouseId!,
      returnTaskId: verifiedLogisticsSeed.returnTaskId,
      rentalItemId: verifiedLogisticsSeed.rentalItemId,
      expectedVersion: verifiedLogisticsSeed.returnTaskVersion,
    })
    if (!claimed.estimateClaimId) {
      throw new Error("Не удалось получить claim возврата")
    }
    estimateClaimRef.current = {
      claimId: claimed.estimateClaimId,
      version: claimed.version,
    }
  }

  async function releaseReturnClaimAfterFailure() {
    const claim = estimateClaimRef.current
    estimateClaimRef.current = null
    if (!claim || !verifiedLogisticsSeed) return
    await releaseReturnEstimateClaim({
      warehouseId: selectedWarehouseId!,
      returnTaskId: verifiedLogisticsSeed.returnTaskId,
      expectedVersion: claim.version,
      claimId: claim.claimId,
    })
  }

  const estimates = listQuery.data ?? []
  const workspaceOpen = createRequested || Boolean(estimateId)
  const detailUnavailable = Boolean(
    estimateId && !detailQuery.isLoading && !detailQuery.data
  )
  const logisticsSeedInvalid = Boolean(
    returnTaskId &&
    (logisticsSeedQuery.isError ||
      (logisticsSeedQuery.isSuccess && !logisticsSeedQuery.data))
  )
  const linkageError = dossierNavigationState?.estimateLinkageError

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

      {linkageError ? (
        <p role="alert" className="text-sm text-destructive">
          {linkageError}
        </p>
      ) : null}

      {workspaceOpen && selectedWarehouseId ? (
        logisticsSeedInvalid ? (
          <Card>
            <CardHeader>
              <CardTitle>Возврат недоступен</CardTitle>
              <CardDescription role="alert">
                Задание возврата изменилось или уже обработано. Вернитесь в
                раздел логистики и откройте его заново.
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
            warehouseId={selectedWarehouseId}
            estimate={estimateId ? (detailQuery.data ?? null) : null}
            loading={Boolean(
              (estimateId && detailQuery.isLoading) ||
              (returnTaskId && logisticsSeedQuery.isLoading)
            )}
            onClose={() => closeEditor()}
            onSaved={handleSaved}
            seed={estimateId ? undefined : verifiedLogisticsSeed}
            initialRentalItemId={
              estimateId || verifiedLogisticsSeed
                ? undefined
                : rentalItemSeed?.rentalItemId
            }
            onBeforePersist={
              verifiedLogisticsSeed ? claimReturnBeforePersist : undefined
            }
            onPersistFailed={
              verifiedLogisticsSeed ? releaseReturnClaimAfterFailure : undefined
            }
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
