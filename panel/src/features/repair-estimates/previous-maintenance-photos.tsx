import { useQuery } from "@tanstack/react-query"

import { PhotoCarousel } from "@/components/media/photo-carousel"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  cabinMediaOwner,
  createHttpMediaClient,
  type MediaAsset,
  type ServiceMediaOwner,
} from "@/features/media/media-service"
import { ServiceOwnerPhotos } from "@/features/media/service-owner-photos"
import {
  listMaintenanceEstimates,
  listMaintenanceRepairs,
} from "@/features/repair-estimates/api/http-maintenance-lifecycle-client"
import {
  selectLatestPreviousMaintenancePhotoSource,
  type CurrentMaintenancePhotoOwner,
} from "@/features/repair-estimates/previous-maintenance-photo-source"
import { ApiError } from "@/lib/api-client"
import { retryOwnerProofOperation } from "@/features/media/owner-proof-retry"

const previousPhotosMediaClient = createHttpMediaClient()

async function listAllCabinMedia(
  accessToken: string,
  owner: ServiceMediaOwner
) {
  const assets: MediaAsset[] = []
  let cursor: string | undefined
  for (let pageNumber = 0; pageNumber < 20; pageNumber += 1) {
    const page = await retryOwnerProofOperation(() =>
      previousPhotosMediaClient.listOwnerMedia(accessToken, owner, {
        limit: 100,
        cursor,
      })
    )
    assets.push(...page.items)
    if (page.next === null) return assets
    cursor = page.next
  }
  throw new Error("История фотографий бытовки слишком велика")
}

async function listAvailableCabinMedia(
  accessToken: string,
  owner: ServiceMediaOwner
) {
  try {
    return await listAllCabinMedia(accessToken, owner)
  } catch (error) {
    if (
      error instanceof ApiError &&
      (error.status === 403 || error.status === 404)
    ) {
      return []
    }
    throw error
  }
}

async function findPreviousPhotos({
  accessToken,
  warehouseId,
  rentalItemId,
  currentOwner,
  currentCreatedAt,
}: {
  accessToken: string
  warehouseId: string
  rentalItemId: string
  currentOwner: CurrentMaintenancePhotoOwner
  currentCreatedAt: string | null
}) {
  const cabinOwner = cabinMediaOwner(rentalItemId, warehouseId)
  const [estimates, repairs, cabinAssets] = await Promise.all([
    listMaintenanceEstimates(accessToken, warehouseId, undefined, rentalItemId),
    listMaintenanceRepairs(accessToken, warehouseId, { rentalItemId }),
    listAvailableCabinMedia(accessToken, cabinOwner),
  ])

  return selectLatestPreviousMaintenancePhotoSource({
    warehouseId,
    rentalItemId,
    estimates: estimates.items,
    repairs: repairs.items,
    cabinAssets,
    currentOwner,
    currentCreatedAt,
  })
}

function formattedDate(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(value))
}

function PreviousPhotosPlaceholder({
  message,
  loading = false,
  onRetry,
}: {
  message: string
  loading?: boolean
  onRetry?: () => void
}) {
  return (
    <section
      className="flex h-full min-h-0 flex-col gap-3"
      aria-label="Фотографии до"
    >
      <div className="flex items-center justify-between gap-2">
        <Badge variant="secondary">Фото до</Badge>
        {onRetry ? (
          <Button type="button" variant="outline" size="sm" onClick={onRetry}>
            Повторить
          </Button>
        ) : null}
      </div>
      <PhotoCarousel
        photos={[]}
        title="Фотографии до"
        loading={loading}
        className="min-h-56 flex-1 rounded-lg border"
        fit="contain"
        controlsVisibility="mobile-visible"
        emptyLabel={message}
      />
    </section>
  )
}

export function PreviousMaintenancePhotos({
  accessToken,
  warehouseId,
  rentalItemId,
  currentOwner,
  currentCreatedAt,
}: {
  accessToken: string | null
  warehouseId: string
  rentalItemId: string
  currentOwner: CurrentMaintenancePhotoOwner
  currentCreatedAt: string | null
}) {
  const query = useQuery({
    queryKey: [
      "previous-maintenance-photos",
      warehouseId,
      rentalItemId,
      currentOwner?.ownerType ?? null,
      currentOwner?.ownerId ?? null,
      currentCreatedAt,
    ],
    queryFn: () =>
      findPreviousPhotos({
        accessToken: accessToken!,
        warehouseId,
        rentalItemId,
        currentOwner,
        currentCreatedAt,
      }),
    enabled: Boolean(accessToken && rentalItemId),
  })

  if (!rentalItemId) {
    return (
      <PreviousPhotosPlaceholder message="Выберите бытовку, чтобы найти фотографии до" />
    )
  }
  if (!accessToken) {
    return <PreviousPhotosPlaceholder message="Сервис фото недоступен" />
  }
  if (query.isLoading) {
    return (
      <PreviousPhotosPlaceholder
        message="Ищем последнюю предыдущую съёмку"
        loading
      />
    )
  }
  if (query.isError) {
    return (
      <PreviousPhotosPlaceholder
        message="Не удалось найти фотографии до"
        onRetry={() => void query.refetch()}
      />
    )
  }
  if (!query.data) {
    return (
      <PreviousPhotosPlaceholder message="Предыдущие фотографии не найдены" />
    )
  }

  return (
    <ServiceOwnerPhotos
      accessToken={accessToken}
      owner={query.data.owner}
      readOnly
      maxItems={Math.max(20, query.data.mediaIds.length)}
      title="Фотографии до"
      visibleMediaIds={query.data.mediaIds}
      toolbarAction={
        <Badge variant="outline">
          {query.data.label} · {formattedDate(query.data.capturedAt)}
        </Badge>
      }
    />
  )
}
