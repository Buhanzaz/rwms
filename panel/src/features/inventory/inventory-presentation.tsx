import { Badge } from "@/components/ui/badge"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"

export function InventoryUnavailable({
  title,
  description,
}: {
  title: string
  description: string
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription role="alert">{description}</CardDescription>
      </CardHeader>
    </Card>
  )
}

export function InventoryReconciliationBadges({
  inspected,
  hasWork,
  added,
  missing,
  conflicts,
}: {
  inspected: boolean
  hasWork: boolean
  added: boolean
  missing: boolean
  conflicts: number
}) {
  return (
    <div className="flex flex-wrap gap-1">
      {inspected ? (
        <Badge>Проверена</Badge>
      ) : (
        <Badge variant="outline">Не проверена</Badge>
      )}
      {hasWork ? <Badge variant="secondary">С работами</Badge> : null}
      {added ? <Badge variant="secondary">Добавлена</Badge> : null}
      {missing ? <Badge variant="destructive">Не найдена</Badge> : null}
      {conflicts > 0 ? (
        <Badge variant="destructive">Конфликты: {conflicts}</Badge>
      ) : null}
    </div>
  )
}

export function InventoryEmptyState({ children }: { children: string }) {
  return (
    <Card size="sm">
      <CardContent className="py-6 text-center text-muted-foreground">
        {children}
      </CardContent>
    </Card>
  )
}
