import { AiChat02Icon, PackageSearchIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { Link } from "react-router-dom"

import { Button } from "@/components/ui/button"
import {
  Card,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"

function orderLinkedPath(
  basePath: "/assistant" | "/booking",
  clientId: string,
  orderId: string
) {
  const query = new URLSearchParams({ clientId, orderId })
  return `${basePath}?${query.toString()}`
}

/** Offers exactly the two supported ways to add cabins to an existing order. */
export function AddCabinsDialog({
  open,
  clientId,
  orderId,
  onOpenChange,
}: {
  open: boolean
  clientId: string
  orderId: string
  onOpenChange: (open: boolean) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Добавить бытовки</DialogTitle>
          <DialogDescription>
            Выберите один из двух существующих способов. Клиент укажет желаемые
            дату и срок аренды в представлении; результат будет добавлен в
            текущий заказ.
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-4 sm:grid-cols-2">
          <Card size="sm">
            <CardHeader>
              <CardTitle>Через AI-чат</CardTitle>
              <CardDescription>
                Уточнить параметры последовательно в связанном с заказом чате.
              </CardDescription>
            </CardHeader>
            <CardFooter>
              <Button asChild className="w-full">
                <Link to={orderLinkedPath("/assistant", clientId, orderId)}>
                  <HugeiconsIcon icon={AiChat02Icon} data-icon="inline-start" />
                  Открыть AI-чат
                </Link>
              </Button>
            </CardFooter>
          </Card>
          <Card size="sm">
            <CardHeader>
              <CardTitle>Обычное бронирование</CardTitle>
              <CardDescription>
                Подобрать бытовки и создать клиентское представление без чата.
              </CardDescription>
            </CardHeader>
            <CardFooter>
              <Button asChild variant="outline" className="w-full">
                <Link to={orderLinkedPath("/booking", clientId, orderId)}>
                  <HugeiconsIcon
                    icon={PackageSearchIcon}
                    data-icon="inline-start"
                  />
                  Открыть бронирование
                </Link>
              </Button>
            </CardFooter>
          </Card>
        </div>
      </DialogContent>
    </Dialog>
  )
}
