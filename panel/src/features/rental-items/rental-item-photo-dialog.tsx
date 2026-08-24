import { useMemo, useState } from "react"

import { FullscreenPhotoViewer } from "@/components/media/fullscreen-photo-viewer"
import { useAuth } from "@/features/auth/use-auth"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { useRentalItemMedia } from "@/features/rental-items/use-rental-item-media"

const FULLSCREEN_MEDIA_VARIANTS = ["LARGE"] as const

type RentalItemPhotoDialogProps = {
  item: RentalItemDto | null
  open: boolean
  activePhotoIndex?: number
  onActivePhotoIndexChange?: (index: number) => void
  onOpenChange: (open: boolean) => void
}

function RentalItemPhotoDialogContent({
  item,
  open,
  activePhotoIndex,
  onActivePhotoIndexChange,
  onOpenChange,
}: {
  item: RentalItemDto
  open: boolean
  activePhotoIndex?: number
  onActivePhotoIndexChange?: (index: number) => void
  onOpenChange: (open: boolean) => void
}) {
  const { accessToken } = useAuth()
  const [internalActiveIndex, setInternalActiveIndex] = useState(0)
  const media = useRentalItemMedia({
    item,
    accessToken,
    initialVariants: FULLSCREEN_MEDIA_VARIANTS,
  })
  const photos = useMemo(
    () =>
      media.archivePhotos.map((photo, index) => ({
        id: photo.id,
        src: photo.variants?.large?.url ?? photo.url,
        alt: `Бытовка ${item.number}, фото ${index + 1} из ${media.archivePhotos.length}`,
      })),
    [item.number, media.archivePhotos]
  )
  const visibleActiveIndex = activePhotoIndex ?? internalActiveIndex

  return (
    <FullscreenPhotoViewer
      photos={photos}
      open={open}
      activeIndex={visibleActiveIndex}
      title={`Фото — ${item.number}`}
      loading={media.isLoading}
      emptyLabel={
        media.error
          ? "Сервис фото недоступен"
          : media.logicalPhotoCount > 0
            ? "Фото обрабатываются"
            : "Нет фото"
      }
      onActiveIndexChange={(index) => {
        setInternalActiveIndex(index)
        onActivePhotoIndexChange?.(index)
      }}
      onSwipeUp={() => onOpenChange(false)}
      onOpenChange={onOpenChange}
    />
  )
}

export function RentalItemPhotoDialog({
  item,
  open,
  activePhotoIndex,
  onActivePhotoIndexChange,
  onOpenChange,
}: RentalItemPhotoDialogProps) {
  return item ? (
    <RentalItemPhotoDialogContent
      item={item}
      open={open}
      activePhotoIndex={activePhotoIndex}
      onActivePhotoIndexChange={onActivePhotoIndexChange}
      onOpenChange={onOpenChange}
    />
  ) : null
}
