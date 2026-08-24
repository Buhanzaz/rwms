import { useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import {
  ArrowLeft01Icon,
  ArrowRight01Icon,
  Camera01Icon,
  Cancel01Icon,
  Image01Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useParams } from "react-router-dom"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Skeleton } from "@/components/ui/skeleton"
import { getPublicCabinPhotoPresentation } from "@/features/rental-items/cabin-photo-presentations-api"
import { ApiError } from "@/lib/api-client"

function formatCreatedAt(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "long",
    timeStyle: "short",
  }).format(date)
}

export function PublicCabinPhotoPresentationPage() {
  const { token = "" } = useParams()
  const [viewerIndex, setViewerIndex] = useState<number | null>(null)
  const presentationQuery = useQuery({
    queryKey: ["public-cabin-photo-presentation", token],
    queryFn: () => getPublicCabinPhotoPresentation(token),
    enabled: token.length > 0,
    retry: (failureCount, error) =>
      !(error instanceof ApiError && error.status === 404) && failureCount < 2,
    staleTime: Number.POSITIVE_INFINITY,
  })
  const photos = useMemo(
    () =>
      [...(presentationQuery.data?.photos ?? [])].sort(
        (left, right) => left.sortOrder - right.sortOrder
      ),
    [presentationQuery.data?.photos]
  )

  if (presentationQuery.isPending) return <PhotoPresentationSkeleton />
  if (presentationQuery.isError || !presentationQuery.data) {
    return (
      <PhotoPresentationState
        title="Представление не найдено"
        description="Проверьте ссылку или попросите отправить представление ещё раз."
      />
    )
  }

  const presentation = presentationQuery.data
  if (photos.length === 0) {
    return (
      <PhotoPresentationState
        title="В представлении пока нет фотографий"
        description="Попросите создать новое представление после обработки фотографий."
      />
    )
  }
  const createdAt = formatCreatedAt(presentation.createdAt)
  const selectedPhoto =
    viewerIndex === null ? null : (photos[viewerIndex] ?? null)

  function showPrevious() {
    setViewerIndex((current) =>
      current === null ? null : (current - 1 + photos.length) % photos.length
    )
  }

  function showNext() {
    setViewerIndex((current) =>
      current === null ? null : (current + 1) % photos.length
    )
  }

  return (
    <main className="min-h-svh bg-muted/30 text-foreground">
      <header className="border-b bg-background/95 backdrop-blur">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-4 py-5 sm:px-6 lg:px-8">
          <div className="min-w-0">
            <p className="text-xs font-semibold tracking-[0.16em] text-primary uppercase">
              RWMS
            </p>
            <h1 className="truncate text-xl font-semibold sm:text-2xl">
              Фотографии бытовки {presentation.cabinNumber}
            </h1>
            {createdAt ? (
              <p className="mt-1 text-sm text-muted-foreground">
                Представление создано {createdAt}
              </p>
            ) : null}
          </div>
          <Badge variant="secondary" className="shrink-0 gap-1.5">
            <HugeiconsIcon icon={Camera01Icon} aria-hidden="true" />
            {photos.length} фото
          </Badge>
        </div>
      </header>

      <section
        aria-label={`Фотографии бытовки ${presentation.cabinNumber}`}
        className="mx-auto grid max-w-7xl grid-cols-1 gap-4 px-4 py-6 sm:grid-cols-2 sm:px-6 lg:px-8 xl:grid-cols-3"
      >
        {photos.map((photo, index) => (
          <button
            key={`${photo.mediaId}:${photo.generation}`}
            type="button"
            aria-label={`Открыть фото ${index + 1} из ${photos.length}`}
            className="group relative overflow-hidden rounded-xl border bg-card text-left shadow-sm outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            onClick={() => setViewerIndex(index)}
          >
            <img
              src={photo.thumbnailUrl}
              alt={`Бытовка ${presentation.cabinNumber}, фото ${index + 1} из ${photos.length}`}
              width={1200}
              height={900}
              loading={index < 3 ? "eager" : "lazy"}
              className="aspect-[4/3] w-full object-cover transition-transform duration-200 group-hover:scale-[1.02]"
            />
            <span className="absolute right-3 bottom-3 rounded-full bg-black/65 px-2.5 py-1 text-xs font-medium text-white backdrop-blur">
              {index + 1} / {photos.length}
            </span>
          </button>
        ))}
      </section>

      <Dialog
        open={selectedPhoto !== null}
        onOpenChange={(open) => {
          if (!open) setViewerIndex(null)
        }}
      >
        <DialogContent
          className="max-h-[94svh] max-w-[min(96vw,90rem)] overflow-hidden bg-black p-0 text-white"
          showCloseButton={false}
          onKeyDown={(event) => {
            if (photos.length < 2) return
            if (event.key === "ArrowLeft") {
              event.preventDefault()
              showPrevious()
            }
            if (event.key === "ArrowRight") {
              event.preventDefault()
              showNext()
            }
          }}
        >
          <DialogHeader className="sr-only">
            <DialogTitle>
              Фотографии бытовки {presentation.cabinNumber}
            </DialogTitle>
            <DialogDescription>
              Полноэкранный просмотр. Используйте стрелки для перелистывания.
            </DialogDescription>
          </DialogHeader>
          {selectedPhoto ? (
            <div className="relative flex h-[86svh] items-center justify-center p-4 sm:p-10">
              <DialogClose asChild>
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  aria-label="Закрыть просмотр"
                  className="absolute top-3 right-3 z-10 rounded-full bg-black/55 text-white hover:bg-black/75 hover:text-white"
                >
                  <HugeiconsIcon icon={Cancel01Icon} aria-hidden="true" />
                </Button>
              </DialogClose>
              <img
                src={selectedPhoto.contentUrl}
                alt={`Бытовка ${presentation.cabinNumber}, фото ${(viewerIndex ?? 0) + 1} из ${photos.length}`}
                width={1920}
                height={1440}
                className="max-h-full max-w-full object-contain"
              />
              {photos.length > 1 ? (
                <>
                  <Button
                    type="button"
                    size="icon"
                    variant="ghost"
                    aria-label="Предыдущее фото"
                    className="absolute left-3 rounded-full bg-black/55 text-white hover:bg-black/75 hover:text-white"
                    onClick={showPrevious}
                  >
                    <HugeiconsIcon icon={ArrowLeft01Icon} aria-hidden="true" />
                  </Button>
                  <Button
                    type="button"
                    size="icon"
                    variant="ghost"
                    aria-label="Следующее фото"
                    className="absolute right-3 rounded-full bg-black/55 text-white hover:bg-black/75 hover:text-white"
                    onClick={showNext}
                  >
                    <HugeiconsIcon icon={ArrowRight01Icon} aria-hidden="true" />
                  </Button>
                </>
              ) : null}
              <span
                aria-live="polite"
                className="absolute bottom-4 left-1/2 -translate-x-1/2 rounded-full bg-black/65 px-3 py-1 text-xs font-medium backdrop-blur"
              >
                {(viewerIndex ?? 0) + 1} / {photos.length}
              </span>
            </div>
          ) : null}
        </DialogContent>
      </Dialog>
    </main>
  )
}

function PhotoPresentationSkeleton() {
  return (
    <main className="min-h-svh bg-muted/30">
      <header className="border-b bg-background">
        <div className="mx-auto flex max-w-7xl flex-col gap-3 px-4 py-5 sm:px-6 lg:px-8">
          <Skeleton className="h-3 w-16" />
          <Skeleton className="h-7 w-72 max-w-full" />
          <Skeleton className="h-4 w-56 max-w-full" />
        </div>
      </header>
      <div className="mx-auto grid max-w-7xl grid-cols-1 gap-4 px-4 py-6 sm:grid-cols-2 sm:px-6 lg:px-8 xl:grid-cols-3">
        {Array.from({ length: 6 }, (_, index) => (
          <Skeleton key={index} className="aspect-[4/3] rounded-xl" />
        ))}
      </div>
    </main>
  )
}

function PhotoPresentationState({
  title,
  description,
}: {
  title: string
  description: string
}) {
  return (
    <main className="flex min-h-svh items-center justify-center bg-muted/30 p-6">
      <div className="flex max-w-md flex-col items-center gap-3 text-center">
        <div className="flex size-12 items-center justify-center rounded-full bg-muted text-muted-foreground">
          <HugeiconsIcon
            icon={Image01Icon}
            className="size-6"
            aria-hidden="true"
          />
        </div>
        <h1 className="text-xl font-semibold">{title}</h1>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>
    </main>
  )
}
