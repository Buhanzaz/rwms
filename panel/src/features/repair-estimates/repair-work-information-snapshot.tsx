import type { ReactNode } from "react"

import {
  Field,
  FieldContent,
  FieldGroup,
  FieldTitle,
} from "@/components/ui/field"

type RepairWorkInformationSnapshotProps = {
  cabinNumber: string
  contextLabel: "От кого" | "Причина" | "Источник"
  contextValue: string
  dispatchDate: string | null
  comment: string
  authorName?: string
  authorLabel?: string
  showComment?: boolean
  status?: ReactNode
}

function formatDateOnly(value: string | null) {
  if (!value) {
    return "—"
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) {
    return value
  }
  return `${match[3]}.${match[2]}.${match[1]}`
}

function SnapshotField({ label, value }: { label: string; value: ReactNode }) {
  return (
    <Field orientation="horizontal" className="items-start gap-3">
      <FieldTitle className="w-32 flex-none shrink-0 text-muted-foreground">
        {label}
      </FieldTitle>
      <FieldContent className="min-w-0">
        <div className="min-w-0 break-words">{value}</div>
      </FieldContent>
    </Field>
  )
}

export function RepairWorkInformationSnapshot({
  cabinNumber,
  contextLabel,
  contextValue,
  dispatchDate,
  comment,
  authorName,
  authorLabel = "Автор",
  showComment = true,
  status,
}: RepairWorkInformationSnapshotProps) {
  return (
    <FieldGroup className="gap-3">
      <SnapshotField label="Бытовка" value={cabinNumber || "—"} />
      <SnapshotField label={contextLabel} value={contextValue || "—"} />
      <SnapshotField label="Прибытие" value={formatDateOnly(dispatchDate)} />
      {authorName !== undefined ? (
        <SnapshotField label={authorLabel} value={authorName || "—"} />
      ) : null}
      {status !== undefined ? (
        <SnapshotField label="Статус" value={status} />
      ) : null}
      {showComment ? (
        <SnapshotField label="Общий комментарий" value={comment || "—"} />
      ) : null}
    </FieldGroup>
  )
}
