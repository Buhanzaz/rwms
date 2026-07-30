import { useMemo, useState } from "react"
import { useNavigate, useSearchParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Add01Icon,
  ArrowLeft01Icon,
  FilterIcon,
} from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { MobileAppRequiredDialog } from "@/components/mobile-app-required-dialog"
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
import { Input } from "@/components/ui/input"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"
import { LogisticsDocumentFilters } from "@/features/logistics/logistics-document-filters"
import {
  getRepairEstimate,
  listRepairEstimates,
  repairEstimateDetailQueryKey,
  repairEstimateListQueryKey,
} from "@/features/repair-estimates/api/repair-estimates-api"
import type {
  RepairEstimateStatus,
  RepairEstimateSummaryDto,
} from "@/features/repair-estimates/model/repair-estimate"
import { RepairEstimateEditorWorkspace } from "@/features/repair-estimates/repair-estimate-editor-workspace"
import {
  EMPTY_REPAIR_ESTIMATE_LIST_FILTERS,
  filterRepairEstimates,
  formatRepairEstimateAuthor,
  repairEstimateAuthorIds,
  repairEstimateAuthorOptions,
  textFilterOptions,
  type RepairEstimateListFilters,
} from "@/features/repair-estimates/repair-estimate-list-filters"
import { listDossierActorDisplays } from "@/features/rental-items/dossier/actor/actor-display-api"
import type { DossierActorDisplay } from "@/features/rental-items/dossier/actor/actor-display"
import { useIsMobile } from "@/hooks/use-mobile"
import { useResponsiveFiltersOpen } from "@/hooks/use-responsive-filters-open"
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

const EMPTY_REPAIR_ESTIMATES: RepairEstimateSummaryDto[] = []

export function RepairEstimatesPage() {
  const { selectedWarehouseId } = useWarehouse()
  const { accessToken, currentUser } = useAuth()
  const isMobile = useIsMobile()
  const canEdit = Boolean(
    selectedWarehouseId &&
    hasWarehouseAccess(currentUser, selectedWarehouseId, "EDIT")
  )
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const estimateId = searchParams.get("estimateId")
  const createRequested = searchParams.get("create") === "1"
  const returnTaskId = searchParams.get("returnTaskId")
  const [mobileCreateDialogRequested, setMobileCreateDialogRequested] =
    useState(false)
  const [search, setSearch] = useState("")
  const [filters, setFilters] = useState<RepairEstimateListFilters>(
    EMPTY_REPAIR_ESTIMATE_LIST_FILTERS
  )
  const { filtersOpen, setFiltersOpen } = useResponsiveFiltersOpen()
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
  const listQuery = useQuery({
    queryKey: repairEstimateListQueryKey(selectedWarehouseId ?? "none"),
    queryFn: () => listRepairEstimates(selectedWarehouseId!),
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
    if (isMobile) {
      setMobileCreateDialogRequested(true)
      return
    }

    const next = new URLSearchParams(searchParams)
    next.delete("estimateId")
    next.delete("returnTaskId")
    next.set("create", "1")
    navigate(`/estimates?${next.toString()}`, workspaceEntryNavigationOptions)
  }

  function closeEditor() {
    clearEstimateSelection()
  }

  function handleMobileCreateDialogOpenChange(open: boolean) {
    setMobileCreateDialogRequested(open)
    if (!open && isMobile && createRequested) {
      clearEstimateSelection()
    }
  }

  function handleSaved() {
    closeEditor()
  }

  const estimates = listQuery.data ?? EMPTY_REPAIR_ESTIMATES
  const authorIds = useMemo(
    () => repairEstimateAuthorIds(estimates),
    [estimates]
  )
  const actorDisplaysQuery = useQuery({
    queryKey: ["repair-estimates", "actor-displays", authorIds],
    queryFn: () => listDossierActorDisplays(accessToken!, authorIds),
    enabled: Boolean(accessToken && authorIds.length > 0),
  })
  const actorsById = useMemo(
    () =>
      new Map<string, DossierActorDisplay>(
        (actorDisplaysQuery.data ?? []).map((actor) => [actor.subjectId, actor])
      ),
    [actorDisplaysQuery.data]
  )
  const sourcePartyOptions = useMemo(
    () => textFilterOptions(estimates.map((estimate) => estimate.sourceParty)),
    [estimates]
  )
  const authorOptions = useMemo(
    () => repairEstimateAuthorOptions(estimates, actorsById),
    [actorsById, estimates]
  )
  const filteredEstimates = useMemo(
    () => filterRepairEstimates(estimates, search, filters, actorsById),
    [actorsById, estimates, filters, search]
  )
  const workspaceOpen = createRequested || Boolean(estimateId)
  const mobileCreateBlocked = isMobile && createRequested
  const mobileCreateDialogOpen =
    isMobile && (mobileCreateBlocked || mobileCreateDialogRequested)
  const detailUnavailable = Boolean(
    estimateId && !detailQuery.isLoading && !detailQuery.data
  )
  const editorAvailable = Boolean(
    workspaceOpen &&
    selectedWarehouseId &&
    !(createRequested && (!canEdit || isMobile)) &&
    !returnTaskId &&
    !detailUnavailable
  )

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 overflow-hidden">
      {workspaceOpen && !editorAvailable ? (
        <EstimateWorkspaceBackToolbar onBack={goBack} />
      ) : null}

      {workspaceOpen && selectedWarehouseId ? (
        mobileCreateBlocked ? null : createRequested && !canEdit ? (
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
            authorDisplayName={
              detailQuery.data
                ? formatRepairEstimateAuthor(
                    detailQuery.data.authorName,
                    actorsById
                  )
                : undefined
            }
            onClose={() => closeEditor()}
            onSaved={handleSaved}
          />
        )
      ) : (
        <>
          <PageToolbar>
            <PageToolbarContent className="max-w-xl">
              <Input
                type="search"
                value={search}
                name="repair-estimates-search"
                autoComplete="off"
                aria-label="Поиск по номеру бытовки, полю «От кого» или автору"
                placeholder="Номер бытовки, от кого или автор…"
                onChange={(event) => setSearch(event.target.value)}
              />
            </PageToolbarContent>

            <PageToolbarActions className="w-full sm:w-auto">
              <Button
                type="button"
                size="icon"
                variant={filtersOpen ? "secondary" : "outline"}
                aria-label={
                  filtersOpen ? "Скрыть фильтры смет" : "Показать фильтры смет"
                }
                aria-controls="repair-estimate-filters"
                aria-expanded={filtersOpen}
                onClick={() => setFiltersOpen((current) => !current)}
              >
                <HugeiconsIcon icon={FilterIcon} aria-hidden="true" />
              </Button>
              {canEdit ? (
                <Button
                  type="button"
                  disabled={!selectedWarehouseId}
                  onClick={openCreateEditor}
                >
                  <HugeiconsIcon icon={Add01Icon} data-icon="inline-start" />
                  Создать смету
                </Button>
              ) : null}
            </PageToolbarActions>
          </PageToolbar>

          <div id="repair-estimate-filters" hidden={!filtersOpen}>
            <LogisticsDocumentFilters
              filters={filters}
              stateOptions={[
                { value: "DRAFT", label: "Требует доработки" },
                { value: "COMPLETED", label: "Завершена" },
              ]}
              dateLabel="Создана"
              showSchedule={false}
              extraFilters={[
                {
                  label: "От кого",
                  options: sourcePartyOptions,
                  selected: filters.sourceParties,
                  onApply: (sourceParties) =>
                    setFilters((current) => ({
                      ...current,
                      sourceParties,
                    })),
                },
                {
                  label: "Автор",
                  options: authorOptions,
                  selected: filters.authorIds,
                  onApply: (authorIds) =>
                    setFilters((current) => ({ ...current, authorIds })),
                },
              ]}
              onChange={(next) =>
                setFilters((current) => ({ ...current, ...next }))
              }
              onReset={() => setFilters(EMPTY_REPAIR_ESTIMATE_LIST_FILTERS)}
            />
          </div>

          <div className="min-h-0 flex-1 overflow-auto md:flex">
            {listQuery.isLoading ? (
              <p className="text-xs text-muted-foreground">Загрузка смет...</p>
            ) : listQuery.isError ? (
              <p role="alert" className="text-xs text-destructive">
                Не удалось загрузить сметы.
              </p>
            ) : (
              <>
                <div
                  className="hidden min-h-full flex-1 md:block"
                  hidden={filteredEstimates.length === 0}
                >
                  <OperationsListGrid
                    className="min-h-full"
                    items={filteredEstimates}
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
                        getSortValue: (estimate) =>
                          formatRepairEstimateAuthor(
                            estimate.authorName,
                            actorsById
                          ),
                        render: (estimate) =>
                          formatRepairEstimateAuthor(
                            estimate.authorName,
                            actorsById
                          ),
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

                {filteredEstimates.length > 0 ? (
                  <div className="grid gap-3 md:hidden">
                    {filteredEstimates.map((estimate) => (
                      <EstimateMobileCard
                        key={estimate.id}
                        estimate={estimate}
                        authorName={formatRepairEstimateAuthor(
                          estimate.authorName,
                          actorsById
                        )}
                        onOpen={() => openEstimate(estimate.id)}
                      />
                    ))}
                  </div>
                ) : (
                  <div
                    role="status"
                    className="flex min-h-full w-full items-center justify-center p-6 text-center text-sm text-muted-foreground"
                  >
                    Сметы по заданным условиям не найдены.
                  </div>
                )}
              </>
            )}
          </div>
        </>
      )}

      <MobileAppRequiredDialog
        open={mobileCreateDialogOpen}
        onOpenChange={handleMobileCreateDialogOpenChange}
        operation="Создание сметы"
      />
    </div>
  )
}

function EstimateWorkspaceBackToolbar({ onBack }: { onBack: () => void }) {
  return (
    <PageToolbar>
      <PageToolbarContent>
        <Button type="button" variant="outline" onClick={onBack}>
          <HugeiconsIcon icon={ArrowLeft01Icon} data-icon="inline-start" />
          Назад
        </Button>
      </PageToolbarContent>
    </PageToolbar>
  )
}

function EstimateMobileCard({
  estimate,
  authorName,
  onOpen,
}: {
  estimate: RepairEstimateSummaryDto
  authorName: string
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
        <span>{authorName}</span>
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
