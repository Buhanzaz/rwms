import { useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Camera01Icon, Image01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { useParams } from "react-router-dom"

import { Badge } from "@/components/ui/badge"
import { FullscreenPhotoViewer } from "@/components/media/fullscreen-photo-viewer"
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
  const viewerPhotos = useMemo(
    () =>
      photos.map((photo, index) => ({
        id: `${photo.mediaId}:${photo.generation}`,
        src: photo.contentUrl,
        alt: `Бытовка ${presentationQuery.data?.cabinNumber ?? ""}, фото ${index + 1} из ${photos.length}`,
      })),
    [photos, presentationQuery.data?.cabinNumber]
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
  const details = [
    ["Габариты", presentation.dimensions],
    ["Отделка", presentation.finishing],
    ["Категория", presentation.category],
    [
      "Линолеум",
      presentation.linoleum === null
        ? null
        : presentation.linoleum
          ? "Да"
          : "Нет",
    ],
  ].filter((detail): detail is [string, string] => detail[1] !== null)

  return (
    <main className="h-svh overflow-y-auto bg-muted/30 text-foreground">
      <header className="border-b bg-background/95 backdrop-blur">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-4 py-5 sm:px-6 lg:px-8">
          <div className="min-w-0">
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

      {details.length > 0 || presentation.characteristics.length > 0 ? (
        <section
          aria-label="Характеристики бытовки"
          className="mx-auto max-w-7xl px-4 pt-6 sm:px-6 lg:px-8"
        >
          <div className="rounded-xl border bg-card p-4 shadow-sm sm:p-5">
            <dl className="grid gap-x-8 gap-y-3 sm:grid-cols-2 lg:grid-cols-4">
              {details.map(([label, value]) => (
                <div key={label} className="min-w-0">
                  <dt className="text-xs text-muted-foreground">{label}</dt>
                  <dd className="mt-0.5 text-sm font-medium break-words">
                    {value}
                  </dd>
                </div>
              ))}
              {presentation.characteristics.length > 0 ? (
                <div className="min-w-0 sm:col-span-2 lg:col-span-4">
                  <dt className="text-xs text-muted-foreground">
                    Характеристики
                  </dt>
                  <dd className="mt-2 flex flex-wrap gap-2">
                    {presentation.characteristics.map((characteristic) => (
                      <Badge key={characteristic} variant="outline">
                        {characteristic}
                      </Badge>
                    ))}
                  </dd>
                </div>
              ) : null}
            </dl>
          </div>
        </section>
      ) : null}

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

      <FullscreenPhotoViewer
        photos={viewerPhotos}
        open={viewerIndex !== null}
        activeIndex={viewerIndex ?? 0}
        title={`Фотографии бытовки ${presentation.cabinNumber}`}
        onActiveIndexChange={setViewerIndex}
        onOpenChange={(open) => {
          if (!open) setViewerIndex(null)
        }}
      />
    </main>
  )
}

function PhotoPresentationSkeleton() {
  return (
    <main className="h-svh overflow-y-auto bg-muted/30">
      <header className="border-b bg-background">
        <div className="mx-auto flex max-w-7xl flex-col gap-3 px-4 py-5 sm:px-6 lg:px-8">
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
    <main className="flex h-svh items-center justify-center overflow-y-auto bg-muted/30 p-6">
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
