import type { CredentialStatus } from "@/features/settings/task-board/model/task-board-settings"

export type WorkerCredentialToggleAction = "ENABLE" | "DISABLE"

export function workerCredentialToggleAction(
  status: CredentialStatus
): WorkerCredentialToggleAction | null {
  if (status === "ACTIVE") return "DISABLE"
  if (status === "DISABLED") return "ENABLE"
  return null
}
