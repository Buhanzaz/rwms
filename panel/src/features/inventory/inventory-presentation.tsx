import { Badge } from "@/components/ui/badge"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import type { InventoryFindingDto } from "@/features/inventory/model/inventory"

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

export type InventoryFindingStatusMode = "ACTIVE" | "COMPLETION"

type InventoryFindingPrimaryStatus = {
  label: string
  variant: "success" | "warning" | "progress" | "muted" | "destructive"
}

function inventoryFindingPrimaryStatus(
  finding: InventoryFindingDto,
  mode: InventoryFindingStatusMode
): InventoryFindingPrimaryStatus {
  if (mode === "COMPLETION") {
    if (
      finding.inspectionStatus === "NOT_INSPECTED" ||
      finding.reconciliationStatus === "MISSING"
    ) {
      return { label: "Не найдено", variant: "destructive" }
    }
    if (finding.lines.length > 0) {
      return { label: "Направлено в ремонт", variant: "progress" }
    }
    return { label: "Проверено", variant: "success" }
  }

  if (finding.inspectionStatus === "NOT_INSPECTED") {
    return { label: "Не проверено", variant: "muted" }
  }
  if (finding.reconciliationStatus !== "MATCHED") {
    return { label: "Ожидает сверки", variant: "warning" }
  }
  return { label: "Проверено", variant: "success" }
}

export function InventoryFindingStatusBadge({
  finding,
  mode,
}: {
  finding: InventoryFindingDto
  mode: InventoryFindingStatusMode
}) {
  const status = inventoryFindingPrimaryStatus(finding, mode)
  return (
    <Badge className="w-fit shrink-0" variant={status.variant}>
      {status.label}
    </Badge>
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
