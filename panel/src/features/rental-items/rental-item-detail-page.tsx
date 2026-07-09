import {
  useMemo,
  useRef,
  useState,
  type PointerEvent,
  type ReactNode,
} from "react"
import { Link, useNavigate, useParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { MoveContentsToRentalItemDialog } from "@/features/rental-items/move-contents-to-rental-item-dialog"
import {
  ArrowRightLeft,
  Camera,
  FileText,
  History,
  Image,
  Minus,
  PackageCheck,
  PencilLine,
  Plus,
  RotateCcw,
  Search,
  Truck,
  Warehouse,
  Wrench,
} from "lucide-react"

import {
  getRentalItem,
  getRentalItemPhotos,
} from "@/features/rental-items/api/rental-items-api"
import { PhotoCarousel } from "@/components/media/photo-carousel"
import { Button } from "@/components/ui/button"
import { AddContentsDialog } from "@/features/rental-items/add-contents-dialog"
import { MoveContentsToStockDialog } from "@/features/rental-items/move-contents-to-stock-dialog"
import { RentalItemPhotoDialog } from "@/features/rental-items/rental-item-photo-dialog"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"
import { useWarehouse } from "@/hooks/use-warehouse"
import { useIsTabletOrSmaller } from "@/hooks/use-mobile"
import {
  formatRentalItemContents,
  RENTAL_ITEM_STATUS_LABEL,
  type RentalItemDto,
  type RentalItemPhotoDto,
} from "@/features/rental-items/model/rental-item"

type DetailTab =
  | "overview"
  | "photos"
  | "inspections"
  | "estimates"
  | "repair"
  | "reserves"
  | "shipments"
  | "returns"
  | "history"
  | "comments"

type ReserveRow = {
  id: string
  client: string
  createdAt: string
  expiresAt: string
  status: string
}

type ContentsRow = {
  name: string
  quantity: number
  unit: string
}

type SwipeBackGesture = {
  pointerId: number
  startX: number
  startY: number
}

const tabs: Array<{
  id: DetailTab
  label: string
  icon: ReactNode
}> = [
  {
    id: "overview",
    label: "Обзор",
    icon: <FileText className="size-4" />,
  },
  {
    id: "photos",
    label: "Фото",
    icon: <Image className="size-4" />,
  },
  {
    id: "inspections",
    label: "Осмотры",
    icon: <Search className="size-4" />,
  },
  {
    id: "estimates",
    label: "Сметы",
    icon: <PackageCheck className="size-4" />,
  },
  {
    id: "repair",
    label: "Ремонт",
    icon: <Wrench className="size-4" />,
  },
  {
    id: "reserves",
    label: "Резервы",
    icon: <Camera className="size-4" />,
  },
  {
    id: "shipments",
    label: "Отгрузки",
    icon: <Truck className="size-4" />,
  },
  {
    id: "returns",
    label: "Возвраты",
    icon: <RotateCcw className="size-4" />,
  },
  {
    id: "history",
    label: "История",
    icon: <History className="size-4" />,
  },
  {
    id: "comments",
    label: "Комментарии",
    icon: <FileText className="size-4" />,
  },
]

const EDGE_SWIPE_START_MAX = 40
const EDGE_SWIPE_MIN_DISTANCE = 72
const EDGE_SWIPE_AXIS_LOCK_RATIO = 1.15

function getItemPhotos(item: RentalItemDto, photos: RentalItemPhotoDto[]) {
  if (photos.length > 0) {
    return photos
  }

  if (item.previewPhotoUrls && item.previewPhotoUrls.length > 0) {
    return item.previewPhotoUrls
  }

  if (item.mainPhotoUrl) {
    return [item.mainPhotoUrl]
  }

  return []
}

function formatMoney(value: number | null) {
  if (value === null) {
    return "—"
  }

  return `${value.toLocaleString("ru-RU")} ₽`
}

function formatBoolean(value: boolean | null) {
  if (value === null) {
    return "—"
  }

  return value ? "Да" : "Нет"
}

function getMockReserveRows(item: RentalItemDto): ReserveRow[] {
  if (!item.tenant && item.status !== "RESERVED" && item.status !== "BOOKED") {
    return []
  }

  const client = item.tenant ?? "ИП Петров А.В."

  return [
    {
      id: `${item.id}-reserve-1`,
      client,
      createdAt: "20.05.2026 12:00",
      expiresAt: "20.05.2026 12:30",
      status: item.status === "RESERVED" ? "Активный резерв" : "Истёк",
    },
    {
      id: `${item.id}-reserve-2`,
      client: "ООО СтройПроект",
      createdAt: "18.05.2026 09:20",
      expiresAt: "18.05.2026 09:50",
      status: "Истёк",
    },
    {
      id: `${item.id}-reserve-3`,
      client: "ООО Монтаж Север",
      createdAt: "15.05.2026 16:10",
      expiresAt: "15.05.2026 16:40",
      status: "Истёк",
    },
  ]
}

function getContentsRows(item: RentalItemDto): ContentsRow[] {
  return item.contentsItems.map((row) => ({
    name: row.name,
    quantity: row.quantity,
    unit: "шт.",
  }))
}

function getContentsStateKey(item: RentalItemDto) {
  const contentsKey = item.contentsItems
    .map((contentItem) => `${contentItem.name}:${contentItem.quantity}`)
    .join("|")

  return `${item.id}:${contentsKey}`
}

function DetailField({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="grid gap-1 border-b py-2 last:border-b-0">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-sm font-medium">{value}</div>
    </div>
  )
}

function DetailCard({
  title,
  children,
}: {
  title: string
  children: ReactNode
}) {
  return (
    <section className="rounded-lg border bg-card p-4">
      <div className="mb-4 border-b pb-3 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        {title}
      </div>

      {children}
    </section>
  )
}

function OverviewValue({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="grid gap-1">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-sm font-medium">{value}</div>
    </div>
  )
}

function EmptyTab({ label }: { label: string }) {
  return (
    <div className="rounded-lg border bg-card p-6 text-sm text-muted-foreground">
      Раздел «{label}» пока не реализован.
    </div>
  )
}

function ContentsCard({ item }: { item: RentalItemDto }) {
  const [addDialogOpen, setAddDialogOpen] = useState(false)
  const [moveToStockDialogOpen, setMoveToStockDialogOpen] = useState(false)
  const [moveToRentalItemDialogOpen, setMoveToRentalItemDialogOpen] =
    useState(false)

  const [draftRows, setDraftRows] = useState(() =>
    getContentsRows(item).map((row) => ({
      ...row,
    }))
  )

  const hasRows = draftRows.length > 0

  const hasChanges = draftRows.some((draftRow) => {
    const originalRow = item.contentsItems.find((row) => {
      return row.name === draftRow.name
    })

    return originalRow?.quantity !== draftRow.quantity
  })

  function decreaseQuantity(name: string) {
    setDraftRows((currentRows) =>
      currentRows.map((row) => {
        if (row.name !== name) {
          return row
        }

        return {
          ...row,
          quantity: Math.max(0, row.quantity - 1),
        }
      })
    )
  }

  function increaseQuantity(name: string) {
    setDraftRows((currentRows) =>
      currentRows.map((row) => {
        if (row.name !== name) {
          return row
        }

        return {
          ...row,
          quantity: row.quantity + 1,
        }
      })
    )
  }

  function applyChanges() {
    console.log("Изменить наполнение бытовки", {
      rentalItemId: item.id,
      contentsItems: draftRows.map((row) => ({
        name: row.name,
        quantity: row.quantity,
      })),
    })
  }

  return (
    <DetailCard title="Наполнение">
      <div className="flex h-[320px] flex-col">
        <div className="min-h-0 flex-1 overflow-auto pr-2">
          {hasRows ? (
            <div className="divide-y rounded-md border">
              {draftRows.map((row) => (
                <div
                  key={row.name}
                  className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-4 px-3 py-2 text-sm"
                >
                  <div className="truncate font-medium">{row.name}</div>

                  <div className="flex items-center gap-2 whitespace-nowrap">
                    <Button
                      type="button"
                      size="icon"
                      variant="ghost"
                      className="size-7"
                      disabled={row.quantity <= 0}
                      onClick={() => decreaseQuantity(row.name)}
                    >
                      <Minus className="size-3.5" />
                    </Button>

                    <div className="min-w-12 text-center text-muted-foreground">
                      <span className="font-semibold text-foreground">
                        {row.quantity}
                      </span>{" "}
                      {row.unit}
                    </div>

                    <Button
                      type="button"
                      size="icon"
                      variant="ghost"
                      className="size-7"
                      onClick={() => increaseQuantity(row.name)}
                    >
                      <Plus className="size-3.5" />
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="flex h-full items-center justify-center rounded-md border bg-muted/30 text-sm text-muted-foreground">
              Наполнение не указано.
            </div>
          )}
        </div>

        <div className="mt-3 grid gap-2 border-t pt-3 sm:grid-cols-2">
          {hasRows ? (
            <>
              <Button
                variant="outline"
                onClick={() => setMoveToRentalItemDialogOpen(true)}
              >
                <ArrowRightLeft className="mr-2 size-4" />
                Переместить
              </Button>

              <Button
                variant="outline"
                onClick={() => setMoveToStockDialogOpen(true)}
              >
                <Warehouse className="mr-2 size-4" />
                Переместить на склад
              </Button>

              {hasChanges && (
                <Button className="sm:col-span-2" onClick={applyChanges}>
                  Изменить
                </Button>
              )}
            </>
          ) : (
            <>
              <Button
                variant="secondary"
                onClick={() => setAddDialogOpen(true)}
              >
                <Plus className="mr-2 size-4" />
                Добавить
              </Button>

              <Button
                variant="secondary"
                onClick={() => setMoveToRentalItemDialogOpen(true)}
              >
                <ArrowRightLeft className="mr-2 size-4" />
                Переместить
              </Button>
            </>
          )}
        </div>
      </div>
      <AddContentsDialog
        item={item}
        open={addDialogOpen}
        onOpenChange={setAddDialogOpen}
      />

      <MoveContentsToRentalItemDialog
        item={item}
        open={moveToRentalItemDialogOpen}
        onOpenChange={setMoveToRentalItemDialogOpen}
      />

      <MoveContentsToStockDialog
        item={item}
        open={moveToStockDialogOpen}
        onOpenChange={setMoveToStockDialogOpen}
      />
    </DetailCard>
  )
}

function OverviewTab({
  item,
  warehouseName,
}: {
  item: RentalItemDto
  warehouseName: string
}) {
  const reserveRows = getMockReserveRows(item)

  return (
    <div className="grid gap-4 xl:grid-cols-2">
      <DetailCard title="Статус и расположение">
        <div className="grid gap-4 sm:grid-cols-2">
          <OverviewValue
            label="Текущий статус"
            value={RENTAL_ITEM_STATUS_LABEL[item.status]}
          />
          <OverviewValue label="Склад" value={warehouseName} />
          <OverviewValue label="Расположение" value="Ряд В, место 11" />
          <OverviewValue label="Готовность к аренде" value="Готова" />
        </div>
      </DetailCard>

      <DetailCard title="Клиент и резерв">
        {reserveRows.length > 0 ? (
          <div className="space-y-3">
            {reserveRows.slice(0, 3).map((reserve) => (
              <div
                key={reserve.id}
                className="rounded-md border bg-muted/30 p-3"
              >
                <div className="mb-2 flex items-center justify-between gap-2">
                  <div className="font-medium">{reserve.client}</div>

                  <span className="rounded-full bg-background px-2 py-0.5 text-xs text-muted-foreground">
                    {reserve.status}
                  </span>
                </div>

                <div className="grid gap-2 text-xs sm:grid-cols-2">
                  <div>
                    <span className="text-muted-foreground">Поставлено:</span>{" "}
                    <span className="font-medium">{reserve.createdAt}</span>
                  </div>

                  <div>
                    <span className="text-muted-foreground">Истекает:</span>{" "}
                    <span className="font-medium">{reserve.expiresAt}</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="text-sm text-muted-foreground">
            Активного клиента и резерва нет.
          </div>
        )}
      </DetailCard>

      <DetailCard title="Осмотр и ремонт">
        <div className="grid gap-4 sm:grid-cols-2">
          <OverviewValue label="Последний осмотр" value="13.05.2026" />
          <OverviewValue label="Ремонт" value="Нет" />
          <OverviewValue label="Плановый осмотр" value="13.05.2027" />
          <OverviewValue label="Ремонтные задачи" value="0" />
        </div>
      </DetailCard>

      <DetailCard title="Характеристики">
        <div className="text-sm font-medium">
          {item.characteristics ?? "Характеристики не указаны."}
        </div>
      </DetailCard>

      <ContentsCard key={getContentsStateKey(item)} item={item} />
    </div>
  )
}

function HeroInfoPanel({
  item,
  onOpenPhotos,
}: {
  item: RentalItemDto
  onOpenPhotos: () => void
}) {
  return (
    <aside className="flex h-full min-h-0 flex-col border-t bg-card lg:border-t-0 lg:border-l">
      <div className="border-b p-4">
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <h2 className="text-2xl font-semibold">{item.number}</h2>

          <RentalItemStatusBadge status={item.status} />

          <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
            {item.type}
          </span>
        </div>

        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="outline">
            <Search className="mr-2 size-4" />
            Создать осмотр
          </Button>

          <Button size="sm" variant="outline">
            <Camera className="mr-2 size-4" />
            Добавить фото
          </Button>

          <Button size="sm" variant="outline">
            <Wrench className="mr-2 size-4" />
            Создать ремонт
          </Button>

          <Button size="sm" variant="outline">
            <PencilLine className="mr-2 size-4" />
            Изменить статус
          </Button>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-auto p-4">
        <DetailField label="Номер" value={item.number} />
        <DetailField label="Тип" value={item.type} />
        <DetailField label="Габариты" value={item.dimensions ?? "—"} />
        <DetailField label="Отделка" value={item.finishing ?? "—"} />
        <DetailField label="Категория" value={item.category ?? "—"} />
        <DetailField
          label="Характеристики"
          value={item.characteristics ?? "—"}
        />
        <DetailField label="Линолеум" value={formatBoolean(item.linoleum)} />
        <DetailField
          label="Статус"
          value={RENTAL_ITEM_STATUS_LABEL[item.status]}
        />
        <DetailField
          label="Наполнение"
          value={formatRentalItemContents(item.contentsItems, item.contents)}
        />
        <DetailField label="Комментарий" value={item.comment ?? "—"} />

        <DetailField
          label="Фото"
          value={
            item.hasPhotos ? (
              <button
                type="button"
                className="inline-flex items-center gap-1 text-primary hover:underline"
                onClick={onOpenPhotos}
              >
                <Image className="size-4" />
                {item.photoCount}
              </button>
            ) : (
              "0"
            )
          }
        />

        <DetailField label="Цена" value={formatMoney(item.price)} />
      </div>
    </aside>
  )
}

export function RentalItemDetailPage() {
  const navigate = useNavigate()
  const { rentalItemId } = useParams()
  const { selectedWarehouse } = useWarehouse()
  const isTabletOrSmaller = useIsTabletOrSmaller()

  const [activeTab, setActiveTab] = useState<DetailTab>("overview")
  const [photoDialogOpen, setPhotoDialogOpen] = useState(false)
  const [activePhotoIndex, setActivePhotoIndex] = useState(0)
  const swipeBackGestureRef = useRef<SwipeBackGesture | null>(null)

  const itemQuery = useQuery({
    queryKey: ["rental-item", rentalItemId],
    queryFn: () => getRentalItem(rentalItemId!),
    enabled: rentalItemId !== undefined,
  })

  const photosQuery = useQuery({
    queryKey: ["rental-item-photos", rentalItemId],
    queryFn: () => getRentalItemPhotos(rentalItemId!),
    enabled: rentalItemId !== undefined,
  })

  const item = itemQuery.data ?? null

  const photoSources = useMemo(() => {
    if (!item) {
      return []
    }

    return getItemPhotos(item, photosQuery.data ?? [])
  }, [item, photosQuery.data])

  const warehouseName =
    selectedWarehouse?.name ??
    selectedWarehouse?.city ??
    selectedWarehouse?.code ??
    "Склад"

  const city =
    selectedWarehouse?.city ?? selectedWarehouse?.code ?? warehouseName

  function navigateBack() {
    if (window.history.length > 1) {
      navigate(-1)
      return
    }

    navigate("/warehouse")
  }

  function handlePointerDown(event: PointerEvent<HTMLDivElement>) {
    if (!isTabletOrSmaller) {
      swipeBackGestureRef.current = null
      return
    }

    if (event.button !== 0 || event.clientX > EDGE_SWIPE_START_MAX) {
      swipeBackGestureRef.current = null
      return
    }

    event.currentTarget.setPointerCapture(event.pointerId)
    swipeBackGestureRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
    }
  }

  function handlePointerUp(event: PointerEvent<HTMLDivElement>) {
    const start = swipeBackGestureRef.current
    swipeBackGestureRef.current = null

    if (!start || !isTabletOrSmaller || event.pointerId !== start.pointerId) {
      return
    }

    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }

    const deltaX = event.clientX - start.startX
    const deltaY = event.clientY - start.startY
    const absX = Math.abs(deltaX)
    const absY = Math.abs(deltaY)
    const isBackSwipe =
      deltaX >= EDGE_SWIPE_MIN_DISTANCE &&
      absX > absY * EDGE_SWIPE_AXIS_LOCK_RATIO

    if (!isBackSwipe) {
      return
    }

    navigateBack()
  }

  if (itemQuery.isLoading) {
    return (
      <div className="flex h-full items-center justify-center rounded-lg border bg-card text-sm text-muted-foreground">
        Загрузка бытовки...
      </div>
    )
  }

  if (!item) {
    return (
      <div className="rounded-lg border bg-card p-6">
        <div className="mb-4 text-sm text-muted-foreground">
          Бытовка не найдена.
        </div>

        <Button variant="outline" onClick={() => navigate("/warehouse")}>
          Вернуться на склад
        </Button>
      </div>
    )
  }

  const selectedTab = tabs.find((tab) => tab.id === activeTab)

  return (
    <div
      className="h-full min-h-0 touch-pan-y overflow-auto"
      onPointerDown={handlePointerDown}
      onPointerUp={handlePointerUp}
      onPointerCancel={(event) => {
        if (event.currentTarget.hasPointerCapture(event.pointerId)) {
          event.currentTarget.releasePointerCapture(event.pointerId)
        }
        swipeBackGestureRef.current = null
      }}
    >
      <div className="flex min-h-full flex-col gap-4 pb-4">
        <nav className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
          <Link to="/warehouse" className="hover:text-foreground">
            Склад
          </Link>
          <span>/</span>
          <Link to="/warehouse" className="hover:text-foreground">
            {city}
          </Link>
          <span>/</span>
          <span className="font-medium text-foreground">{item.number}</span>
        </nav>

        <section className="overflow-hidden rounded-lg border bg-card">
          <div className="grid lg:h-[calc(100svh-14rem)] lg:max-h-[720px] lg:min-h-[560px] lg:grid-cols-[minmax(0,1fr)_360px] 2xl:grid-cols-[minmax(0,1fr)_420px]">
            <PhotoCarousel
              photos={photoSources}
              photoCount={item.photoCount}
              showPhotoCount={item.hasPhotos}
              className="h-[320px] bg-muted lg:h-full"
              fit="contain"
              controlsVisibility="mobile-visible"
              activeIndex={activePhotoIndex}
              onActiveIndexChange={setActivePhotoIndex}
              onCenterClick={
                item.hasPhotos ? () => setPhotoDialogOpen(true) : undefined
              }
            />

            <HeroInfoPanel
              item={item}
              onOpenPhotos={() => setPhotoDialogOpen(true)}
            />
          </div>
        </section>

        <div className="sticky top-0 z-30 rounded-lg border bg-background/95 p-2 shadow-sm backdrop-blur">
          <div className="flex flex-wrap gap-2">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                type="button"
                className={[
                  "inline-flex h-9 items-center gap-2 rounded-md border px-3 text-sm transition",
                  activeTab === tab.id
                    ? "border-primary bg-primary text-primary-foreground"
                    : "bg-card text-muted-foreground hover:bg-muted hover:text-foreground",
                ].join(" ")}
                onClick={() => setActiveTab(tab.id)}
              >
                {tab.icon}
                {tab.label}
              </button>
            ))}
          </div>
        </div>

        <div>
          {activeTab === "overview" ? (
            <OverviewTab item={item} warehouseName={warehouseName} />
          ) : (
            <EmptyTab label={selectedTab?.label ?? "Раздел"} />
          )}
        </div>

        <RentalItemPhotoDialog
          item={item}
          open={photoDialogOpen}
          activePhotoIndex={activePhotoIndex}
          onActivePhotoIndexChange={setActivePhotoIndex}
          onOpenChange={setPhotoDialogOpen}
        />
      </div>
    </div>
  )
}
