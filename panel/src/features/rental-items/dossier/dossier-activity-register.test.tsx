import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "@/lib/api-client"
import { DossierActivityRegister } from "@/features/rental-items/dossier/dossier-activity-register"
import type { CabinDossierPage } from "@/features/rental-items/dossier/model/dossier-service"

const CABIN_ID = "10000000-0000-0000-0000-000000000001"
const ACTOR_ID = "40000000-0000-0000-0000-000000000001"

function page(overrides: Partial<CabinDossierPage> = {}): CabinDossierPage {
  return {
    cabinId: CABIN_ID,
    activities: [
      {
        activityId: "30000000-0000-0000-0000-000000000001",
        cabinId: CABIN_ID,
        warehouseId: "20000000-0000-0000-0000-000000000001",
        activityCode: "CABIN_CREATED",
        occurredAt: null,
        recordedAt: "2026-07-18T12:00:00Z",
        actorRef: {
          subjectId: ACTOR_ID,
          principalType: "USER",
          profileRevision: null,
        },
        sourceRef: {
          producer: "asset-service",
          aggregateType: "RENTAL_ITEM",
          aggregateId: CABIN_ID,
        },
        media: [],
      },
    ],
    nextCursor: "next-page",
    visibility: "PARTIAL",
    ...overrides,
  }
}

afterEach(cleanup)

describe("DossierActivityRegister", () => {
  it("shows truthful partial coverage, opaque actor/source refs and load-more", () => {
    const onLoadMore = vi.fn()
    render(
      <DossierActivityRegister
        pages={[page()]}
        error={null}
        isLoading={false}
        hasNextPage
        isFetchingNextPage={false}
        onLoadMore={onLoadMore}
      />
    )

    expect(screen.getByText("PARTIAL")).toBeTruthy()
    expect(screen.getByText("Загружено: 1")).toBeTruthy()
    expect(screen.getByText("CABIN_CREATED")).toBeTruthy()
    expect(screen.getByText(`USER · ${ACTOR_ID}`)).toBeTruthy()
    expect(
      screen.getByText(`asset-service · RENTAL_ITEM · ${CABIN_ID}`)
    ).toBeTruthy()
    expect(screen.queryByText("Иван Петров")).toBeNull()

    fireEvent.click(screen.getByRole("button", { name: "Загрузить ещё" }))
    expect(onLoadMore).toHaveBeenCalledTimes(1)
  })

  it("keeps PARTIAL visible for an empty filtered page", () => {
    render(
      <DossierActivityRegister
        pages={[page({ activities: [], nextCursor: null })]}
        error={null}
        isLoading={false}
        hasNextPage={false}
        isFetchingNextPage={false}
        onLoadMore={vi.fn()}
      />
    )

    expect(screen.getByText("PARTIAL")).toBeTruthy()
    expect(screen.getByText("Загружено: 0")).toBeTruthy()
    expect(screen.getByText("Событий нет")).toBeTruthy()
  })

  it.each([
    [404, "Досье ещё не сформировано"],
    [403, "Нет доступа к досье"],
  ])("renders HTTP %s without fallback rows", (status, title) => {
    render(
      <DossierActivityRegister
        pages={undefined}
        error={new ApiError("upstream", status)}
        isLoading={false}
        hasNextPage={false}
        isFetchingNextPage={false}
        onLoadMore={vi.fn()}
      />
    )

    expect(screen.getByText(title)).toBeTruthy()
    expect(screen.queryByRole("table")).toBeNull()
  })
})
