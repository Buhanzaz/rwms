import { useEffect, useState } from "react"
import { useInfiniteQuery, useQuery } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Search01Icon,
  Tick02Icon,
  UnfoldMoreIcon,
} from "@hugeicons/core-free-icons"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import {
  ESTIMATE_RENTAL_ITEMS_QUERY_KEY,
  resolveEstimateRentalItem,
  searchEstimateRentalItems,
} from "@/features/repair-estimates/api/repair-estimates-api"
import type { EstimateRentalItemOptionDto } from "@/features/repair-estimates/model/repair-estimate"
import {
  REPAIR_TASK_RENTAL_ITEMS_QUERY_KEY,
  resolveRepairTaskRentalItem,
  searchRepairTaskRentalItems,
} from "@/features/repair-tasks/api/repair-tasks-api"
import { cn } from "@/lib/utils"

const PAGE_SIZE = 40

export type RepairWorkRentalItemScope = "ESTIMATE" | "REPAIR"

type RepairEstimateRentalItemPickerProps = {
  id: string
  warehouseId: string
  value: string
  scope?: RepairWorkRentalItemScope
  invalid?: boolean
  disabled?: boolean
  onValueChange: (rentalItem: EstimateRentalItemOptionDto) => void
}

function formatArrivalDate(value: string) {
  const [year, month, day] = value.split("-")
  return year && month && day ? `${day}.${month}.${year}` : value
}

function RentalItemMetadata({ item }: { item: EstimateRentalItemOptionDto }) {
  const values = [
    item.counterparty ? `Контрагент: ${item.counterparty}` : null,
    item.arrivalDate
      ? `Осмотр: ${formatArrivalDate(item.arrivalDate)}`
      : null,
  ].filter((value): value is string => Boolean(value))

  return values.length > 0 ? (
    <span className="block truncate text-xs text-muted-foreground">
      {values.join(" · ")}
    </span>
  ) : null
}

export function RepairEstimateRentalItemPicker({
  id,
  warehouseId,
  value,
  scope = "ESTIMATE",
  invalid = false,
  disabled = false,
  onValueChange,
}: RepairEstimateRentalItemPickerProps) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [debouncedSearch, setDebouncedSearch] = useState("")

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedSearch(search), 300)
    return () => window.clearTimeout(timer)
  }, [search])

  const rentalItemsQueryKey =
    scope === "REPAIR"
      ? REPAIR_TASK_RENTAL_ITEMS_QUERY_KEY
      : ESTIMATE_RENTAL_ITEMS_QUERY_KEY
  const searchRentalItems =
    scope === "REPAIR" ? searchRepairTaskRentalItems : searchEstimateRentalItems
  const resolveRentalItem =
    scope === "REPAIR" ? resolveRepairTaskRentalItem : resolveEstimateRentalItem

  const searchQuery = useInfiniteQuery({
    queryKey: [...rentalItemsQueryKey, warehouseId, "search", debouncedSearch],
    queryFn: ({ pageParam }) =>
      searchRentalItems({
        warehouseId,
        search: debouncedSearch,
        page: pageParam,
        size: PAGE_SIZE,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
    enabled: open && Boolean(warehouseId),
  })
  const selectedQuery = useQuery({
    queryKey: [...rentalItemsQueryKey, warehouseId, "resolve", value],
    queryFn: () => resolveRentalItem(warehouseId, value),
    enabled: Boolean(warehouseId && value),
  })
  const items = searchQuery.data?.pages.flatMap((page) => page.items) ?? []
  const selectedFromSearch = items.find((item) => item.id === value)
  const selected = selectedFromSearch ?? selectedQuery.data

  return (
    <Popover
      open={disabled ? false : open}
      onOpenChange={(nextOpen) => {
        if (!disabled) {
          if (nextOpen) {
            setSearch("")
            setDebouncedSearch("")
          }
          setOpen(nextOpen)
        }
      }}
    >
      <PopoverTrigger asChild>
        <Button
          id={id}
          type="button"
          role="combobox"
          variant="outline"
          disabled={disabled}
          aria-label="Бытовка"
          aria-expanded={open}
          aria-invalid={invalid || undefined}
          onFocus={() => {
            if (!disabled) {
              setSearch("")
              setDebouncedSearch("")
              setOpen(true)
            }
          }}
          className={cn(
            "w-full justify-between font-normal",
            !selected && "text-muted-foreground"
          )}
        >
          <span className="min-w-0 text-left">
            <span className="block truncate">
              {selected?.number ?? (value ? "Загрузка..." : "Выберите бытовку")}
            </span>
            {scope === "ESTIMATE" && selected ? (
              <RentalItemMetadata item={selected} />
            ) : null}
          </span>
          <HugeiconsIcon icon={UnfoldMoreIcon} data-icon="inline-end" />
        </Button>
      </PopoverTrigger>

      <PopoverContent
        align="start"
        className="w-[var(--radix-popover-trigger-width)] gap-2 p-2"
      >
        <div className="relative">
          <HugeiconsIcon
            icon={Search01Icon}
            className="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            aria-label="Поиск бытовки"
            className="pl-8"
            value={search}
            placeholder="Номер бытовки"
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>

        <div
          className="max-h-64 touch-pan-y overflow-y-auto overscroll-contain"
          onScroll={(event) => {
            const { clientHeight, scrollHeight, scrollTop } =
              event.currentTarget
            const reachedListEnd = scrollHeight - scrollTop - clientHeight <= 48

            if (
              reachedListEnd &&
              searchQuery.hasNextPage &&
              !searchQuery.isFetchingNextPage
            ) {
              void searchQuery.fetchNextPage()
            }
          }}
        >
          {searchQuery.isLoading ? (
            <p className="px-2 py-3 text-xs text-muted-foreground">
              Загрузка бытовок...
            </p>
          ) : searchQuery.isError ? (
            <p role="alert" className="px-2 py-3 text-xs text-destructive">
              Не удалось загрузить бытовки.
            </p>
          ) : items.length === 0 ? (
            <p className="px-2 py-3 text-xs text-muted-foreground">
              Бытовки не найдены.
            </p>
          ) : (
            <div className="flex flex-col gap-1">
              {items.map((item) => (
                <Button
                  key={item.id}
                  type="button"
                  variant={item.id === value ? "secondary" : "ghost"}
                  className="w-full justify-start"
                  onClick={() => {
                    onValueChange(item)
                    setOpen(false)
                  }}
                >
                  {item.id === value ? (
                    <HugeiconsIcon icon={Tick02Icon} data-icon="inline-start" />
                  ) : null}
                  <span className="min-w-0 text-left">
                    <span className="block truncate">{item.number}</span>
                    {scope === "ESTIMATE" ? (
                      <RentalItemMetadata item={item} />
                    ) : null}
                  </span>
                </Button>
              ))}
            </div>
          )}
        </div>

        {searchQuery.isFetchingNextPage ? (
          <p role="status" className="px-2 text-xs text-muted-foreground">
            Загрузка бытовок...
          </p>
        ) : null}
      </PopoverContent>
    </Popover>
  )
}
