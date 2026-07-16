import { cleanup, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

const { resolveOriginalMediaUrl } = vi.hoisted(() => ({
  resolveOriginalMediaUrl: vi.fn(async (mediaId: string) =>
    Promise.resolve(`blob:original-${mediaId}`)
  ),
}))

vi.mock("@/features/media/api/media-original-api", () => ({
  resolveOriginalMediaUrl,
}))

import type { OriginalMediaViewerContext } from "@/features/media/model/media"
import { useOriginalMediaUrls } from "@/features/media/use-original-media-urls"

afterEach(() => {
  cleanup()
  resolveOriginalMediaUrl.mockClear()
})

function Harness({
  enabled,
  context = "WORK",
}: {
  enabled: boolean
  context?: OriginalMediaViewerContext
}) {
  const urls = useOriginalMediaUrls(
    [{ id: "media-1", originalAvailable: true }],
    enabled,
    context
  )
  return <output>{urls["media-1"] ?? "preview-only"}</output>
}

describe("useOriginalMediaUrls", () => {
  it("does not retrieve an original while the preference is disabled", async () => {
    render(<Harness enabled={false} />)

    await waitFor(() => expect(screen.getByText("preview-only")).toBeTruthy())
    expect(resolveOriginalMediaUrl).not.toHaveBeenCalled()
  })

  it("retrieves an original lazily in each permitted work context", async () => {
    const { rerender } = render(<Harness enabled context="ESTIMATE" />)
    await screen.findByText("blob:original-media-1")
    expect(resolveOriginalMediaUrl).toHaveBeenLastCalledWith(
      "media-1",
      "ESTIMATE"
    )

    rerender(<Harness enabled context="INSPECTION" />)
    await waitFor(() =>
      expect(resolveOriginalMediaUrl).toHaveBeenLastCalledWith(
        "media-1",
        "INSPECTION"
      )
    )

    rerender(<Harness enabled context="WORK" />)
    await waitFor(() =>
      expect(resolveOriginalMediaUrl).toHaveBeenLastCalledWith(
        "media-1",
        "WORK"
      )
    )
  })
})
