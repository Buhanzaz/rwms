import { useMemo, useRef, useState } from "react"
import { useMutation, useQuery } from "@tanstack/react-query"
import {
  CheckmarkCircle02Icon,
  Clock01Icon,
  Image01Icon,
  Loading03Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useParams } from "react-router-dom"

import { PhotoCarousel } from "@/components/media/photo-carousel"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  confirmPublicPresentation,
  getPublicPresentation,
  getPublicPresentationBooking,
  type PresentationBooking,
  type PresentationCabin,
} from "@/features/assistant/api/rental-presentations-api"
import { OrderCommandIdentityRegistry } from "@/features/orders/api/order-command-identity"
import { ApiError } from "@/lib/api-client"
import { cn } from "@/lib/utils"

export function PublicClientPresentationPage() {
  const { token = "" } = useParams()
  const presentationQuery = useQuery({
    queryKey: ["public-client-presentation", token],
    queryFn: () => getPublicPresentation(token),
    retry: (count, error) =>
      !(error instanceof ApiError && [404, 410].includes(error.status)) &&
      count < 2,
  })
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [booking, setBooking] = useState<PresentationBooking | null>(null)
  const bookingCommand = useRef(new OrderCommandIdentityRegistry())
  const bookingQuery = useQuery({
    queryKey: ["public-presentation-booking", token, booking?.bookingId],
    queryFn: () =>
      getPublicPresentationBooking({
        token,
        bookingId: booking!.bookingId,
      }),
    enabled: booking?.state === "PENDING",
    refetchInterval: (query) =>
      query.state.data?.state === "PENDING" ? 1_500 : false,
  })
  const effectiveBooking = bookingQuery.data ?? booking
  const confirmMutation = useMutation({
    mutationFn: async () => {
      const selectedRentalItemIds = [...selectedIds].sort()
      const fingerprint = JSON.stringify({
        token,
        selectedRentalItemIds,
      })
      const value = await confirmPublicPresentation({
        token,
        selectedRentalItemIds,
        idempotencyKey: bookingCommand.current.keyFor(fingerprint),
      })
      return { fingerprint, value }
    },
    onSuccess: ({ fingerprint, value }) => {
      bookingCommand.current.confirm(fingerprint)
      setBooking(value)
      setConfirmOpen(false)
    },
  })

  if (presentationQuery.isPending) {
    return <PublicState loading text="Открываем представление…" />
  }
  if (presentationQuery.isError) {
    const expired =
      presentationQuery.error instanceof ApiError &&
      presentationQuery.error.status === 410
    return (
      <PublicState
        text={
          expired
            ? "Срок просмотра этого представления истёк."
            : "Представление не найдено или ссылка больше не действует."
        }
      />
    )
  }

  const presentation = presentationQuery.data
  const viewOnly = presentation.viewOnly === true
  const completed = effectiveBooking?.state === "COMPLETED"
  const rejected = effectiveBooking?.state === "REJECTED"

  return (
    <main className="h-svh overflow-y-auto bg-muted/30 text-foreground">
      <header className="sticky top-0 z-40 border-b bg-background/95 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3 sm:px-6">
          <div>
            <p className="text-base font-bold tracking-tight">RWMS</p>
            <p className="text-xs text-muted-foreground">
              Подборка бытовок для аренды
            </p>
          </div>
          <Select
            onValueChange={(value) =>
              document
                .getElementById(`offer-group-${value}`)
                ?.scrollIntoView({ behavior: "smooth", block: "start" })
            }
          >
            <SelectTrigger
              aria-label="Перейти к группе бытовок"
              className="w-48 sm:w-64"
            >
              <SelectValue placeholder="Перейти к группе" />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {presentation.groups.map((group) => (
                  <SelectItem key={group.key} value={group.key}>
                    {group.label}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </div>
      </header>

      <div className="mx-auto max-w-6xl px-4 pt-8 pb-[calc(10rem+env(safe-area-inset-bottom))] sm:px-6">
        <div className="mb-8 rounded-2xl border bg-background p-5 shadow-sm">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1 className="text-2xl font-semibold tracking-tight">
                Доступные бытовки
              </h1>
              <p className="mt-2 max-w-2xl text-sm text-muted-foreground">
                Посмотрите фотографии и характеристики, затем отметьте
                подходящие варианты. Выбор создаст черновик бронирования у
                вашего менеджера.
              </p>
            </div>
            <Badge variant={viewOnly ? "outline" : "secondary"}>
              <HugeiconsIcon icon={Clock01Icon} />
              {viewOnly
                ? "Только просмотр"
                : `Удержание до ${formatPublicDate(presentation.expiresAt)}`}
            </Badge>
          </div>
        </div>

        <div className="space-y-12">
          {presentation.groups.map((group) => (
            <section
              id={`offer-group-${group.key}`}
              key={group.key}
              className="scroll-mt-24"
            >
              <div className="mb-4 flex items-end justify-between gap-4">
                <div>
                  <h2 className="text-xl font-semibold">{group.label}</h2>
                  <p className="text-sm text-muted-foreground">
                    {group.cabins.length}{" "}
                    {formatCabinCount(group.cabins.length)}
                  </p>
                </div>
              </div>
              <div className="space-y-5">
                {group.cabins.map((cabin) => (
                  <PublicCabinCard
                    key={cabin.id}
                    cabin={cabin}
                    selected={selectedIds.has(cabin.id)}
                    disabled={viewOnly || Boolean(effectiveBooking)}
                    onSelectedChange={(selected) => {
                      const next = new Set(selectedIds)
                      if (selected) next.add(cabin.id)
                      else next.delete(cabin.id)
                      setSelectedIds(next)
                    }}
                  />
                ))}
              </div>
            </section>
          ))}
        </div>
      </div>

      <div className="sticky bottom-0 z-40 border-t bg-background/95 px-4 pt-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4">
          <div className="text-sm">
            {completed ? (
              <span className="inline-flex items-center gap-2 font-medium text-emerald-700">
                <HugeiconsIcon icon={CheckmarkCircle02Icon} />
                Выбор отправлен менеджеру
              </span>
            ) : rejected ? (
              <span className="text-destructive">
                Выбранные бытовки уже недоступны. Обратитесь к менеджеру.
              </span>
            ) : effectiveBooking?.state === "PENDING" ? (
              <span className="inline-flex items-center gap-2">
                <HugeiconsIcon icon={Loading03Icon} className="animate-spin" />
                Создаём бронирование…
              </span>
            ) : viewOnly ? (
              <span className="text-muted-foreground">
                Срок удержания истёк — представление доступно только для
                просмотра.
              </span>
            ) : (
              <>
                Выбрано: <strong>{selectedIds.size}</strong>
              </>
            )}
          </div>
          {!viewOnly && !effectiveBooking ? (
            <Button
              type="button"
              disabled={selectedIds.size === 0}
              onClick={() => setConfirmOpen(true)}
            >
              Создать бронирование
            </Button>
          ) : null}
        </div>
      </div>

      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Подтвердить выбор?</DialogTitle>
            <DialogDescription>
              В черновик бронирования попадут выбранные бытовки (
              {selectedIds.size}). Остальные временные удержания будут сняты.
            </DialogDescription>
          </DialogHeader>
          {confirmMutation.isError ? (
            <p className="text-sm text-destructive">
              {confirmMutation.error instanceof Error
                ? confirmMutation.error.message
                : "Не удалось создать бронирование."}
            </p>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setConfirmOpen(false)}
            >
              Вернуться
            </Button>
            <Button
              type="button"
              disabled={confirmMutation.isPending}
              onClick={() => confirmMutation.mutate()}
            >
              {confirmMutation.isPending ? (
                <HugeiconsIcon icon={Loading03Icon} className="animate-spin" />
              ) : null}
              Подтвердить
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </main>
  )
}

function PublicCabinCard({
  cabin,
  selected,
  disabled,
  onSelectedChange,
}: {
  cabin: PresentationCabin
  selected: boolean
  disabled: boolean
  onSelectedChange: (selected: boolean) => void
}) {
  const passport = useMemo(
    () =>
      Object.entries(cabin.passport ?? {}).filter(
        ([key, value]) =>
          !/^legacy/i.test(key) &&
          !/^source$/i.test(key) &&
          !/^(locationNodeId|hasPhotos|photoCount|mainPhotoUrl|previewPhotoUrls)$/i.test(
            key
          ) &&
          !/(author|audit|action|created|updated|version|internal)/i.test(
            key
          ) &&
          ["string", "number", "boolean"].includes(typeof value)
      ),
    [cabin.passport]
  )
  const photos = cabin.photos.map((photo) => ({
    id: photo.mediaId,
    url: photo.thumbnailUrl,
    variants: {
      small: { url: photo.thumbnailUrl },
      medium: { url: photo.contentUrl },
      large: { url: photo.contentUrl },
    },
  }))

  return (
    <article
      className={cn(
        "overflow-hidden rounded-2xl border bg-background shadow-sm transition sm:grid sm:grid-cols-[minmax(0,1.15fr)_minmax(18rem,0.85fr)]",
        selected && "border-primary ring-2 ring-primary/15"
      )}
    >
      <PhotoCarousel
        photos={photos}
        title={`Бытовка ${cabin.number}`}
        className="aspect-[4/3] rounded-none sm:aspect-auto sm:min-h-80"
        imageClassName="h-full"
        fullscreenQuality="original"
        controlsVisibility="mobile-visible"
        placeholder={
          <div className="flex size-full min-h-72 items-center justify-center bg-muted text-muted-foreground">
            <HugeiconsIcon icon={Image01Icon} className="size-10" />
          </div>
        }
      />
      <div className="flex flex-col p-5 sm:p-6">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-xs font-medium tracking-wide text-primary uppercase">
              {cabin.rentalType ?? "Бытовка"}
            </p>
            <h3 className="mt-1 text-xl font-semibold">
              Бытовка {cabin.number}
            </h3>
          </div>
          <label
            className={cn(
              "flex cursor-pointer items-center gap-2 rounded-full border px-3 py-2 text-sm font-medium",
              selected && "border-primary bg-primary/5 text-primary",
              disabled && "cursor-default opacity-60"
            )}
          >
            <Checkbox
              checked={selected}
              disabled={disabled}
              aria-label={`Выбрать бытовку ${cabin.number}`}
              onCheckedChange={(value) => onSelectedChange(value === true)}
            />
            {selected ? "Выбрана" : "Выбрать"}
          </label>
        </div>
        <dl className="mt-6 grid grid-cols-2 gap-x-5 gap-y-4 text-sm">
          <Characteristic label="Габариты" value={cabin.dimensions} />
          <Characteristic label="Отделка" value={cabin.finishing} />
          <Characteristic label="Категория" value={cabin.category} />
          <Characteristic
            label="Линолеум"
            value={
              cabin.linoleum === null ? null : cabin.linoleum ? "Да" : "Нет"
            }
          />
          {passport.map(([key, value]) => (
            <Characteristic key={key} label={key} value={String(value)} />
          ))}
        </dl>
        {cabin.characteristics ? (
          <div className="mt-6 border-t pt-4">
            <p className="text-xs font-medium text-muted-foreground">
              Дополнительные характеристики
            </p>
            <p className="mt-2 text-sm whitespace-pre-wrap">
              {cabin.characteristics}
            </p>
          </div>
        ) : null}
        {cabin.tags.length > 0 ? (
          <div className="mt-auto flex flex-wrap gap-2 pt-5">
            {cabin.tags.map((tag) => (
              <Badge key={tag} variant="outline">
                {tag}
              </Badge>
            ))}
          </div>
        ) : null}
      </div>
    </article>
  )
}

function Characteristic({
  label,
  value,
}: {
  label: string
  value: string | null
}) {
  if (!value) return null
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-1 font-medium">{value}</dd>
    </div>
  )
}

function PublicState({
  text,
  loading = false,
}: {
  text: string
  loading?: boolean
}) {
  return (
    <main className="flex min-h-svh flex-col items-center justify-center gap-4 bg-muted/30 p-6 text-center">
      <div className="flex size-12 items-center justify-center rounded-2xl bg-background shadow-sm">
        <HugeiconsIcon
          icon={loading ? Loading03Icon : Clock01Icon}
          className={cn("size-6", loading && "animate-spin")}
        />
      </div>
      <p className="max-w-md text-sm text-muted-foreground">{text}</p>
    </main>
  )
}

function formatPublicDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat("ru-RU", {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(date)
}

function formatCabinCount(count: number) {
  const last = count % 10
  const lastTwo = count % 100
  if (last === 1 && lastTwo !== 11) return "бытовка"
  if ([2, 3, 4].includes(last) && ![12, 13, 14].includes(lastTwo)) {
    return "бытовки"
  }
  return "бытовок"
}
