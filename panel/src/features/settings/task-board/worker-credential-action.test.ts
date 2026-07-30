import { describe, expect, it } from "vitest"

import { workerCredentialToggleAction } from "@/features/settings/task-board/worker-credential-action"

describe("workerCredentialToggleAction", () => {
  it("maps active and disabled credentials to opposite commands", () => {
    expect(workerCredentialToggleAction("ACTIVE")).toBe("DISABLE")
    expect(workerCredentialToggleAction("DISABLED")).toBe("ENABLE")
  })

  it.each(["NOT_CONFIGURED", "PENDING", "ERROR"] as const)(
    "does not offer a toggle command for %s credentials",
    (status) => {
      expect(workerCredentialToggleAction(status)).toBeNull()
    }
  )
})
