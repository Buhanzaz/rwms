import { type ReactNode, useMemo, useState } from "react"
import { keepPreviousData, useQuery } from "@tanstack/react-query"
import {
  ArrowDown01Icon,
  ArrowUp01Icon,
  Image01Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { Link } from "react-router-dom"

import { PhotoCarousel } from "@/components/media/photo-carousel"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Carousel,
  CarouselContent,
  CarouselDots,
  CarouselItem,
  CarouselNext,
  CarouselPrevious,
} from "@/components/ui/carousel"
import { Checkbox } from "@/components/ui/checkbox"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import type {
  AvailableCabin,
  CabinSearchResult,
} from "@/features/assistant/api/assistant-api"
import { cabinSearchGroupKey } from "@/features/assistant/api/assistant-api"
import { assistantSearchGroupLabel } from "@/features/assistant/assistant-search-selection"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import type { CabinCoverProjection } from "@/features/media/media-service"
import {
  loadRentalItemCoverPage,
  useRentalItemCardPhotos,
} from "@/features/rental-items/use-rental-item-covers"
import { cn } from "@/lib/utils"

export function AssistantSearchResults({
  accessToken,
  result,
  selectedIds,
  onSelectionChange,
  selectionPending = false,
  collapsed = false,
  onCollapsedChange,
  footer,
}: {
  accessToken: string
  result: CabinSearchResult
  selectedIds: ReadonlySet<string>
  onSelectionChange: (next: Set<string>) => void
  selectionPending?: boolean
  collapsed?: boolean
  onCollapsedChange?: (collapsed: boolean) => void
  footer?: ReactNode
}) {
  const groups = useMemo(
    () =>
      result.groups.map((entry) => ({
        entry,
        key: cabinSearchGroupKey(entry.group),
      })),
    [result.groups]
  )
  const [activeGroupKey, setActiveGroupKey] = useState(
    () => groups[0]?.key ?? null
  )
  const cabinIds = useMemo(
    () =>
      [
        ...new Set(
          result.groups.flatMap((entry) =>
            entry.cabins.map((cabin) => cabin.id)
          )
        ),
      ].sort((left, right) => left.localeCompare(right)),
    [result.groups]
  )
  const coversQuery = useQuery({
    queryKey: [
      "assistant-cabin-covers",
      result.warehouseId,
      cabinIds.join(","),
    ],
    queryFn: () =>
      loadRentalItemCoverPage(accessToken, result.warehouseId, cabinIds),
    enabled: cabinIds.length > 0,
    placeholderData: keepPreviousData,
  })
  const projections = useMemo(
    () =>
      new Map(
        (coversQuery.data?.items ?? []).map((projection) => [
          projection.cabinId,
          projection,
        ])
      ),
    [coversQuery.data?.items]
  )

  const activeGroup =
    groups.find((group) => group.key === activeGroupKey) ?? groups[0] ?? null
  const resolvedActiveGroupKey = activeGroup?.key ?? ""
  const entry = activeGroup?.entry
  if (!entry) return null
  const hasCabins = groups.some((group) => group.entry.cabins.length > 0)
  const coverAvailability = coversQuery.isPending
    ? "loading"
    : coversQuery.isError
      ? "unavailable"
      : "available"
  // Keep small result sets fully visible. A carousel only adds value once a
  // fifth cabin would no longer fit in the four-card desktop layout.
  const carouselEnabled = entry.cabins.length > 4

  return (
    <section
      aria-label="Найденные доступные бытовки"
      className="bg-background px-6 py-4"
    >
      <div className="mx-auto max-w-6xl">
        <div className="flex justify-center pb-1">
          <Button
            type="button"
            size="sm"
            variant="ghost"
            aria-label={
              collapsed ? "Развернуть подбор бытовок" : "Скрыть подбор бытовок"
            }
            aria-pressed={!collapsed}
            onClick={() => onCollapsedChange?.(!collapsed)}
          >
            <HugeiconsIcon
              icon={collapsed ? ArrowUp01Icon : ArrowDown01Icon}
              aria-hidden="true"
            />
            {collapsed ? "Развернуть" : "Скрыть"}
          </Button>
        </div>
        <Tabs value={resolvedActiveGroupKey} onValueChange={setActiveGroupKey}>
          <div className="mb-3 min-w-0 [scrollbar-width:none] overflow-x-auto pb-1 [&::-webkit-scrollbar]:hidden">
            <TabsList
              aria-label="Группы найденных бытовок"
              className="mx-auto flex w-max items-center justify-start gap-1 rounded-full bg-transparent p-0"
            >
              {groups.map(({ entry: group, key: groupKey }, index) => (
                <TabsTrigger
                  key={groupKey}
                  value={groupKey}
                  className="h-8 shrink-0 rounded-full bg-muted/70 px-3 text-foreground/70 shadow-none after:hidden hover:bg-muted hover:text-foreground data-[state=active]:bg-muted data-[state=active]:text-foreground data-[state=active]:shadow-sm"
                >
                  {assistantSearchGroupLabel(group.group, index)}
                  <Badge
                    variant="secondary"
                    className="ml-1 rounded-full bg-background/80"
                  >
                    {group.cabins.length}
                  </Badge>
                </TabsTrigger>
              ))}
            </TabsList>
          </div>

          <TabsContent value={resolvedActiveGroupKey}>
            {!collapsed && entry.cabins.length === 0 ? (
              <div className="flex h-40 items-center justify-center rounded-xl border border-dashed bg-background text-sm text-muted-foreground">
                Доступные бытовки по этой группе не найдены.
              </div>
            ) : !collapsed && carouselEnabled ? (
              <Carousel
                key={resolvedActiveGroupKey}
                orientation="horizontal"
                opts={{ align: "start", dragFree: true }}
                className="px-9"
                data-slot="assistant-cabin-search-carousel"
                aria-label="Карусель найденных бытовок"
              >
                <CarouselContent className="-ml-3">
                  {entry.cabins.map((cabin) => (
                    <CarouselItem
                      key={cabin.id}
                      className="basis-[88%] pl-3 sm:basis-1/2 lg:basis-1/3 xl:basis-1/4"
                    >
                      <AssistantCabinSearchCard
                        accessToken={accessToken}
                        cabin={cabin}
                        projection={projections.get(cabin.id)}
                        coverAvailability={coverAvailability}
                        selectedIds={selectedIds}
                        selectionPending={selectionPending}
                        onSelectionChange={onSelectionChange}
                      />
                    </CarouselItem>
                  ))}
                </CarouselContent>
                <CarouselPrevious className="left-0" />
                <CarouselNext className="right-0" />
                <CarouselDots
                  className="mt-2"
                  aria-label="Навигация по найденным бытовкам"
                  getDotLabel={(index, count) =>
                    `Перейти к бытовке ${index + 1} из ${count}`
                  }
                />
              </Carousel>
            ) : !collapsed ? (
              <div
                role="list"
                aria-label="Карточки найденных бытовок"
                className={cn(
                  "mx-auto grid w-full gap-3",
                  entry.cabins.length === 1 && "max-w-sm grid-cols-1",
                  entry.cabins.length === 2 &&
                    "max-w-2xl grid-cols-1 sm:grid-cols-2",
                  entry.cabins.length === 3 &&
                    "max-w-6xl grid-cols-1 sm:grid-cols-2 md:grid-cols-3",
                  entry.cabins.length === 4 &&
                    "max-w-6xl grid-cols-1 sm:grid-cols-2 md:grid-cols-4"
                )}
              >
                {entry.cabins.map((cabin) => (
                  <div key={cabin.id} role="listitem" className="min-w-0">
                    <AssistantCabinSearchCard
                      accessToken={accessToken}
                      cabin={cabin}
                      projection={projections.get(cabin.id)}
                      coverAvailability={coverAvailability}
                      selectedIds={selectedIds}
                      selectionPending={selectionPending}
                      onSelectionChange={onSelectionChange}
                    />
                  </div>
                ))}
              </div>
            ) : null}
          </TabsContent>
        </Tabs>
        {!collapsed && hasCabins && footer ? (
          <div
            data-slot="assistant-search-results-footer"
            className="mt-3 flex flex-wrap items-center justify-center gap-3 pb-1"
          >
            {footer}
          </div>
        ) : null}
      </div>
    </section>
  )
}

function AssistantCabinSearchCard({
  accessToken,
  cabin,
  projection,
  coverAvailability,
  selectedIds,
  selectionPending,
  onSelectionChange,
}: {
  accessToken: string
  cabin: AvailableCabin
  projection: CabinCoverProjection | undefined
  coverAvailability: "loading" | "available" | "unavailable"
  selectedIds: ReadonlySet<string>
  selectionPending: boolean
  onSelectionChange: (next: Set<string>) => void
}) {
  return (
    <AssistantCabinCard
      accessToken={accessToken}
      cabin={cabin}
      projection={projection}
      coverAvailability={coverAvailability}
      selected={selectedIds.has(cabin.id)}
      disabled={selectionPending}
      onSelectedChange={(selected) => {
        const next = new Set(selectedIds)
        if (selected) next.add(cabin.id)
        else next.delete(cabin.id)
        onSelectionChange(next)
      }}
    />
  )
}

function AssistantCabinCard({
  accessToken,
  cabin,
  projection,
  coverAvailability,
  selected,
  disabled,
  onSelectedChange,
}: {
  accessToken: string
  cabin: AvailableCabin
  projection: CabinCoverProjection | undefined
  coverAvailability: "loading" | "available" | "unavailable"
  selected: boolean
  disabled: boolean
  onSelectedChange: (selected: boolean) => void
}) {
  const media = useRentalItemCardPhotos({
    accessToken,
    cabinId: cabin.id,
    warehouseId: cabin.warehouseId,
    projection,
    coverAvailability,
  })
  const cabinNumber = cabin.number ?? "без номера"

  return (
    <article
      className={cn(
        "relative flex h-full flex-col overflow-hidden rounded-xl border bg-card shadow-sm transition",
        selected && "border-primary ring-2 ring-primary/20"
      )}
    >
      <div className="relative">
        <PhotoCarousel
          photos={[...media.photos]}
          loading={media.availability === "loading"}
          title={`Бытовка ${cabinNumber}`}
          className="aspect-[4/3] rounded-none"
          imageClassName="h-full"
          fullscreenQuality="original"
          onRequestFullscreen={media.requestFullscreen}
          controlsVisibility="mobile-visible"
          placeholder={
            <div className="flex size-full items-center justify-center bg-muted text-muted-foreground">
              <HugeiconsIcon icon={Image01Icon} className="size-8" />
            </div>
          }
        />
        <label className="absolute top-3 right-3 z-20 flex size-8 cursor-pointer items-center justify-center rounded-full border bg-background/95 shadow-sm">
          <Checkbox
            aria-label={`Выбрать бытовку ${cabinNumber}`}
            checked={selected}
            disabled={disabled}
            onCheckedChange={(value) => onSelectedChange(value === true)}
          />
        </label>
      </div>
      <Link
        to={`/warehouse/${cabin.id}`}
        aria-label={`Открыть бытовку ${cabinNumber}`}
        className="flex flex-1 flex-col gap-3 p-4 transition-colors hover:bg-muted/50 focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h3 className="truncate font-semibold">Бытовка {cabinNumber}</h3>
            <p className="mt-1 truncate text-xs text-muted-foreground">
              {[
                cabin.rentalType,
                cabin.dimensions,
                cabin.finishing,
                cabin.category,
              ]
                .filter(Boolean)
                .join(" · ") || "Характеристики не заполнены"}
            </p>
          </div>
          <RentalItemStatusBadge status={cabin.status} />
        </div>
        {cabin.characteristics ? (
          <p className="line-clamp-2 text-xs text-muted-foreground">
            {cabin.characteristics}
          </p>
        ) : null}
      </Link>
    </article>
  )
}
