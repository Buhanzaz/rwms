import { WriteOffsPage } from "./write-offs-page"

/** Legacy route retained as the clear loss view; decisions remain maintenance-owned. */
export function EquipmentWriteOffsPage() {
  return <WriteOffsPage initialDisposition="LOSS" />
}
