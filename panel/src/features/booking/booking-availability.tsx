import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"

export function BookingUnavailableDialog({
  items,
  onAcknowledge,
}: {
  items: readonly RentalItemDto[]
  onAcknowledge: () => void
}) {
  return (
    <AlertDialog
      open={items.length > 0}
      onOpenChange={(open) => {
        if (!open) onAcknowledge()
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>
            {items.length === 1
              ? "Бытовка уже недоступна"
              : "Некоторые бытовки уже недоступны"}
          </AlertDialogTitle>
          <AlertDialogDescription>
            Извините, другой пользователь уже зарезервировал выбранные бытовки.
            Мы удалили их из текущего подбора. Пожалуйста, выберите другие.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <ul
          aria-label="Недоступные бытовки"
          className="grid gap-2 rounded-lg border bg-muted/30 p-3 text-sm"
        >
          {items.map((item) => (
            <li key={item.id}>
              <span className="font-medium">{item.number}</span>
              <span className="text-muted-foreground">
                {" — "}
                {item.type || "Тип не указан"}
                {" — "}
                {item.category || "Категория не указана"}
              </span>
            </li>
          ))}
        </ul>
        <AlertDialogFooter>
          <AlertDialogAction onClick={onAcknowledge}>ОК</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
