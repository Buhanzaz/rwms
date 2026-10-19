import type {
  InventoryFindingDto,
  InventorySessionDto,
} from "@/features/inventory/model/inventory"

export const inventoryFindingPublicationLabel: Record<
  InventoryFindingDto["publicationStatus"],
  string
> = {
  NOT_REQUIRED: "Не требуется",
  READY: "Готова к передаче",
  PUBLISHING: "Передаётся",
  PUBLISHED: "Передана",
  BLOCKED: "Заблокирована",
  FAILED: "Ошибка передачи",
}

const publicationLabel: Record<
  InventorySessionDto["publicationStatus"],
  string
> = {
  NOT_REQUESTED: "Не запрашивалась",
  PENDING: "Ожидает передачи",
  PARTIAL: "Передана частично",
  PUBLISHED: "Передана",
  FAILED: "Ошибка передачи",
}

export function inventoryPublicationLabel(session: InventorySessionDto) {
  const workFindings = session.findings.filter(
    (finding) => finding.lines.length > 0 && !finding.preserveOperationalState
  )
  if (
    session.publicationStatus === "FAILED" &&
    workFindings.some((finding) => finding.publicationStatus === "BLOCKED") &&
    !workFindings.some((finding) => finding.publicationStatus === "FAILED")
  ) {
    return "Передача заблокирована"
  }
  return publicationLabel[session.publicationStatus]
}

export type InventoryPublicationNotice = {
  kind: "success" | "warning" | "error" | "info"
  message: string
}

export function inventoryPublicationNotice(
  session: InventorySessionDto
): InventoryPublicationNotice {
  const workFindings = session.findings.filter(
    (finding) => finding.lines.length > 0 && !finding.preserveOperationalState
  )
  const published = workFindings.filter(
    (finding) => finding.publicationStatus === "PUBLISHED"
  ).length
  const blocked = workFindings.filter(
    (finding) => finding.publicationStatus === "BLOCKED"
  ).length
  const failed = workFindings.filter(
    (finding) => finding.publicationStatus === "FAILED"
  ).length

  if (workFindings.length === 0 && session.publicationStatus === "PUBLISHED") {
    return {
      kind: "success",
      message: "Результаты опубликованы без создания новых ремонтов",
    }
  }
  if (session.publicationStatus === "PUBLISHED") {
    return { kind: "success", message: "Все работы переданы в ремонты" }
  }
  if (session.publicationStatus === "PARTIAL") {
    return {
      kind: "warning",
      message: `Работы переданы частично: передано ${published}, заблокировано ${blocked}, ошибок ${failed}`,
    }
  }
  if (failed > 0) {
    return {
      kind: "error",
      message: `Не удалось передать работы: ошибок ${failed}, заблокировано ${blocked}`,
    }
  }
  if (blocked > 0) {
    return {
      kind: "warning",
      message: `Передача работ заблокирована конфликтами: ${blocked}`,
    }
  }
  return {
    kind: "info",
    message:
      session.publicationStatus === "PENDING"
        ? "Передача работ ещё выполняется"
        : "Работы не передавались",
  }
}
