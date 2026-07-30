import { Badge } from "@/components/ui/badge"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import type {
  InventoryMembershipMovementDto,
  InventoryWarehouseSnapshot,
} from "@/features/inventory/model/inventory"
import { RentalItemStatusBadge } from "@/features/rental-items/rental-item-status-badge"

const movementLabel: Record<InventoryMembershipMovementDto["type"], string> = {
  DEPARTED: "Уехала",
  TRANSFERRED: "Перемещена",
  ARRIVED: "Приехала",
}

const originLabel: Record<InventoryMembershipMovementDto["origin"], string> = {
  ADDED_NEW: "Добавлена новая",
  ADDED_USED: "Добавлена б/у",
  EXPECTED: "Из реестра",
  UNEXPECTED_EXISTING: "Найдена вручную",
}

function warehouseLabel(
  warehouseId: string | null,
  warehouse: InventoryWarehouseSnapshot
) {
  if (!warehouseId) return "—"
  return warehouseId === warehouse.id ? warehouse.name : "Другой склад"
}

function occurredAtLabel(value: string, timeZone: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "short",
    timeStyle: "short",
    timeZone,
  }).format(new Date(value))
}

function MovementStatus({
  status,
}: {
  status: InventoryMembershipMovementDto["status"]
}) {
  return status ? <RentalItemStatusBadge status={status} /> : "—"
}

function MovementRows({
  movements,
  warehouse,
}: {
  movements: InventoryMembershipMovementDto[]
  warehouse: InventoryWarehouseSnapshot
}) {
  return movements.map((movement) => (
    <TableRow key={movement.id}>
      <TableCell className="font-medium">
        {movement.displayCanonicalNumber}
      </TableCell>
      <TableCell>{movementLabel[movement.type]}</TableCell>
      <TableCell>
        {warehouseLabel(movement.fromWarehouseId, warehouse)}
      </TableCell>
      <TableCell>{warehouseLabel(movement.toWarehouseId, warehouse)}</TableCell>
      <TableCell>
        <MovementStatus status={movement.status} />
      </TableCell>
      <TableCell>{movement.tenantSnapshot?.trim() || "—"}</TableCell>
      <TableCell>
        {occurredAtLabel(movement.occurredAt, warehouse.timeZone)}
      </TableCell>
      <TableCell>
        <Badge variant="secondary">{originLabel[movement.origin]}</Badge>
      </TableCell>
    </TableRow>
  ))
}

function MovementCards({
  movements,
  warehouse,
}: {
  movements: InventoryMembershipMovementDto[]
  warehouse: InventoryWarehouseSnapshot
}) {
  return movements.map((movement) => (
    <Card key={movement.id} size="sm">
      <CardHeader>
        <CardTitle>{movement.displayCanonicalNumber}</CardTitle>
        <CardDescription>{movementLabel[movement.type]}</CardDescription>
        <CardAction>
          <Badge variant="secondary">{originLabel[movement.origin]}</Badge>
        </CardAction>
      </CardHeader>
      <CardContent>
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-2 text-sm">
          <dt className="text-muted-foreground">Откуда</dt>
          <dd>{warehouseLabel(movement.fromWarehouseId, warehouse)}</dd>
          <dt className="text-muted-foreground">Куда</dt>
          <dd>{warehouseLabel(movement.toWarehouseId, warehouse)}</dd>
          <dt className="text-muted-foreground">Статус</dt>
          <dd>
            <MovementStatus status={movement.status} />
          </dd>
          <dt className="text-muted-foreground">Арендатор</dt>
          <dd>{movement.tenantSnapshot?.trim() || "—"}</dd>
          <dt className="text-muted-foreground">Когда</dt>
          <dd>{occurredAtLabel(movement.occurredAt, warehouse.timeZone)}</dd>
        </dl>
      </CardContent>
    </Card>
  ))
}

export function InventoryMembershipMovements({
  movements,
  warehouse,
}: {
  movements: InventoryMembershipMovementDto[]
  warehouse: InventoryWarehouseSnapshot
}) {
  return (
    <section
      className="flex flex-col gap-3"
      aria-labelledby="inventory-membership-movements-title"
    >
      <Card>
        <CardHeader>
          <CardTitle id="inventory-membership-movements-title">
            Изменения состава
          </CardTitle>
          <CardDescription>
            Уехавшие, перемещённые и прибывшие бытовки за время инвентаризации.
          </CardDescription>
          <CardAction>
            <Badge variant="secondary">{movements.length}</Badge>
          </CardAction>
        </CardHeader>
        <CardContent>
          {movements.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              Во время этой инвентаризации состав не менялся.
            </p>
          ) : (
            <>
              <div className="hidden overflow-x-auto md:block">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Номер</TableHead>
                      <TableHead>Изменение</TableHead>
                      <TableHead>Откуда</TableHead>
                      <TableHead>Куда</TableHead>
                      <TableHead>Статус</TableHead>
                      <TableHead>Арендатор</TableHead>
                      <TableHead>Когда</TableHead>
                      <TableHead>Источник</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    <MovementRows movements={movements} warehouse={warehouse} />
                  </TableBody>
                </Table>
              </div>
              <div className="grid gap-3 md:hidden">
                <MovementCards movements={movements} warehouse={warehouse} />
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </section>
  )
}
