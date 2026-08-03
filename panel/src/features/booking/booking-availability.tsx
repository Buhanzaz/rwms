import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

export function BookingUnavailableDialog({
  items,
  onAcknowledge,
}: {
  items: readonly RentalItemDto[]
  onAcknowledge: () => void
}) {
  const numbers = items.map((item) => item.number).join(", ")

  return (
    <AlertDialog
      open={items.length > 0}
      onOpenChange={(open) => {
        if (!open) onAcknowledge()
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Бытовка уже забронирована</AlertDialogTitle>
          <AlertDialogDescription>
            Извините, {items.length === 1 ? "бытовку" : "бытовки"} {numbers} уже
            выбрал другой пользователь. Мы удалили их из вашего выбора.
            Пожалуйста, выберите {items.length === 1 ? "другую" : "другие"}.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <Button type="button" onClick={onAcknowledge}>
            ОК
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
