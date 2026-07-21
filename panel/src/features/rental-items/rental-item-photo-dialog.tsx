import { HugeiconsIcon } from "@hugeicons/react"
import { Cancel01Icon } from "@hugeicons/core-free-icons"

import { PhotoCarousel } from "@/components/media/photo-carousel"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
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
  activePhotoIndex,
  onActivePhotoIndexChange,
  onClose,
}: {
  item: RentalItemDto
  activePhotoIndex?: number
  onActivePhotoIndexChange?: (index: number) => void
  onClose: () => void
}) {
  const { accessToken } = useAuth()
  const media = useRentalItemMedia({
    item,
    accessToken,
    initialVariants: FULLSCREEN_MEDIA_VARIANTS,
  })

  return (
    <>
      <div className="absolute top-4 left-4 z-30 max-w-[calc(100vw-6rem)] rounded-full border border-white/15 bg-black/40 px-4 py-2 text-sm font-medium text-white backdrop-blur-md">
        Фото — {item.number}
      </div>
      <div className="absolute top-4 right-4 z-30">
        <Button
          type="button"
          size="icon"
          variant="ghost"
          aria-label="Закрыть"
          className="size-10 rounded-full border border-white/15 bg-black/45 text-white shadow-xl backdrop-blur-md hover:border-black/10 hover:bg-white/90 hover:text-black/70"
          onClick={onClose}
        >
          <HugeiconsIcon icon={Cancel01Icon} />
        </Button>
      </div>
      {media.error && media.photos.length === 0 ? (
        <div className="flex h-dvh items-center justify-center px-6 text-center text-sm text-white/70">
          Сервис фото недоступен
        </div>
      ) : (
        <PhotoCarousel
          photos={media.photos}
          item={item}
          loading={media.isLoading}
          photoCount={media.logicalPhotoCount}
          showPhotoCount={false}
          emptyLabel={
            media.logicalPhotoCount > 0 ? "Фото обрабатываются" : "Нет фото"
          }
          className="h-dvh w-screen bg-black"
          imageVariant="fullscreen"
          fit="contain"
          controlsVisibility="always"
          showViewerToolbar
          disableFullscreenViewer
          onRequestFullscreen={media.requestFullscreen}
          activeIndex={activePhotoIndex}
          onActiveIndexChange={onActivePhotoIndexChange}
          onSwipeUp={onClose}
        />
      )}
    </>
  )
}

export function RentalItemPhotoDialog({
  item,
  open,
  activePhotoIndex,
  onActivePhotoIndexChange,
  onOpenChange,
}: RentalItemPhotoDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="!fixed !inset-0 !top-0 !left-0 !h-dvh !max-h-dvh !w-screen !max-w-none !translate-x-0 !translate-y-0 overflow-hidden rounded-none border-0 bg-black p-0 text-white data-[state=closed]:slide-out-to-bottom data-[state=open]:slide-in-from-bottom lg:data-[state=closed]:fade-out-0 lg:data-[state=open]:fade-in-0 [&>button]:hidden">
        <DialogHeader className="sr-only">
          <DialogTitle>Фото {item ? `— ${item.number}` : ""}</DialogTitle>
        </DialogHeader>
        {item ? (
          <RentalItemPhotoDialogContent
            item={item}
            activePhotoIndex={activePhotoIndex}
            onActivePhotoIndexChange={onActivePhotoIndexChange}
            onClose={() => onOpenChange(false)}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}
