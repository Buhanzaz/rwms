import { X } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

type RentalItemPhotoDialogProps = {
  item: RentalItemDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function RentalItemPhotoDialog({
  item,
  open,
  onOpenChange,
}: RentalItemPhotoDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className={[
          "!fixed !inset-0 !top-0 !left-0 !h-dvh !max-h-dvh !w-screen !max-w-none !translate-x-0 !translate-y-0",
          "overflow-hidden rounded-none border-0 bg-black p-0 text-white",
          "data-[state=closed]:slide-out-to-bottom data-[state=open]:slide-in-from-bottom",
          "lg:data-[state=closed]:fade-out-0 lg:data-[state=open]:fade-in-0",
          "[&>button]:hidden",
        ].join(" ")}
      >
        <DialogHeader className="sr-only">
          <DialogTitle>Фото {item ? `— ${item.number}` : ""}</DialogTitle>
        </DialogHeader>

        <div className="absolute top-4 left-4 z-[90] max-w-[calc(100vw-6rem)] rounded-full border border-white/15 bg-black/40 px-4 py-2 text-sm font-medium text-white backdrop-blur-md">
          Фото {item ? `— ${item.number}` : ""}
        </div>

        <div className="absolute top-4 right-4 z-[100]">
          <Button
            type="button"
            size="icon"
            variant="ghost"
            aria-label="Закрыть"
            className={[
              "size-10 rounded-full border border-white/15",
              "bg-black/45 text-white shadow-xl backdrop-blur-md",
              "transition-colors duration-150",
              "hover:border-black/10 hover:bg-white/90 hover:text-black/70",
            ].join(" ")}
            onClick={() => onOpenChange(false)}
          >
            <X className="size-5" />
          </Button>
        </div>

        <div className="flex h-dvh items-center justify-center px-6 text-center text-sm text-white/70">
          Фотографии бытовки недоступны: asset-service пока не предоставляет
          публичный media API.
        </div>
      </DialogContent>
    </Dialog>
  )
}
