import type { PropsWithChildren } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type {
  CabinDossierPage,
  DossierActivity,
  DossierActivityCode,
} from "@/features/rental-items/dossier/model/dossier-service"
import type {
  OrderMovement,
  OrderUnitCandidate,
} from "@/features/orders/domain/orders"

const dossierApi = vi.hoisted(() => ({ getRentalItemDossierPage: vi.fn() }))

vi.mock("@/features/rental-items/dossier/api/rental-item-dossier-api", () => ({
  rentalItemDossierQueryKey: (rentalItemId: string, filters: unknown) => [
    "rental-item-dossier",
    rentalItemId,
    filters,
  ],
  getRentalItemDossierPage: dossierApi.getRentalItemDossierPage,
}))
vi.mock("@/features/rental-items/dossier/dossier-activity-register", () => ({
  DossierActivityRegister: ({
    hasNextPage,
    onLoadMore,
    error,
  }: PropsWithChildren<{
    hasNextPage: boolean
    onLoadMore: () => void
    error: unknown
  }>) => (
    <div>
      {error ? <span>register-error</span> : null}
      {hasNextPage ? (
        <button type="button" onClick={onLoadMore}>
          Загрузить ещё
        </button>
      ) : null}
    </div>
  ),
}))

import { OrderUnitDossierEvidence } from "@/features/orders/components/order-unit-dossier-evidence"

const CABIN_ID = "11111111-1111-4111-8111-111111111111"
const WAREHOUSE_ID = "22222222-2222-4222-8222-222222222222"
const ORDER_CREATED_AT = "2026-08-01T09:00:00Z"

const candidate: OrderUnitCandidate = {
  reservationId: "33333333-3333-4333-8333-333333333333",
  added: true,
  reservationState: "ACTIVE",
  desiredContents: [],
  unit: {
    id: CABIN_ID,
    version: 1,
    warehouseId: WAREHOUSE_ID,
    number: "БЫТ-001",
    status: "RESERVED",
    rentalType: "БК-1",
    dimensions: "6x2.4",
    finishing: "ЛДСП",
    category: "Обычная",
    characteristics: null,
    linoleum: true,
    tags: [],
    contents: [],
    createdAt: "2026-01-01T08:00:00Z",
    updatedAt: "2026-08-01T08:00:00Z",
  },
}

function activity(code: DossierActivityCode): DossierActivity {
  return {
    activityId: crypto.randomUUID(),
    cabinId: CABIN_ID,
    warehouseId: WAREHOUSE_ID,
    activityCode: code,
    occurredAt: "2026-08-09T10:00:00Z",
    recordedAt: "2026-08-09T10:00:01Z",
    actorRef: null,
    sourceRef: {
      producer: "maintenance-service",
      aggregateType: "REPAIR",
      aggregateId: "44444444-4444-4444-8444-444444444444",
    },
    media: [],
    taskEvidencePhotos: [],
  }
}

function page({
  activities = [],
  nextCursor = null,
  visibility = "COMPLETE",
}: Partial<CabinDossierPage> = {}): CabinDossierPage {
  return { cabinId: CABIN_ID, activities, nextCursor, visibility }
}

function movement(
  documentId: string,
  documentType: OrderMovement["documentType"],
  actualAt: string | null
): OrderMovement {
  return {
    documentId,
    documentType,
    state: actualAt ? "COMPLETED" : "PLANNED",
    scheduledDate: "2026-08-09",
    actualAt,
    rentalShipmentId: null,
    createdAt: "2026-08-08T08:00:00Z",
    updatedAt: "2026-08-09T10:00:00Z",
    cabins: [{ rentalItemId: CABIN_ID, lineState: "COMPLETED" }],
  }
}

function renderEvidence(movements: OrderMovement[] = []) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <OrderUnitDossierEvidence
        accessToken="token"
        orderId="55555555-5555-4555-8555-555555555555"
        orderCreatedAt={ORDER_CREATED_AT}
        candidates={[candidate]}
        movements={movements}
      />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  dossierApi.getRentalItemDossierPage.mockResolvedValue(page())
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("OrderUnitDossierEvidence", () => {
  it("proves estimate-without-repair only after complete pagination is exhausted", async () => {
    dossierApi.getRentalItemDossierPage.mockImplementation(
      (_token: string, _cabinId: string, query: { after?: string }) =>
        query.after
          ? Promise.resolve(page())
          : Promise.resolve(
              page({
                activities: [activity("ESTIMATE_COMPLETED")],
                nextCursor: "second-page",
              })
            )
    )
    const user = userEvent.setup()
    renderEvidence()

    expect(
      await screen.findByText(
        "Смета есть; данных недостаточно, чтобы подтвердить отсутствие ремонта после неё."
      )
    ).toBeTruthy()
    await user.click(screen.getByRole("button", { name: "Загрузить ещё" }))

    expect(
      await screen.findByText("Смета есть, ремонт после неё не зафиксирован.")
    ).toBeTruthy()
    expect(screen.getByText("Подтверждено событием")).toBeTruthy()
    expect(screen.getByText("Подтверждённо отсутствует")).toBeTruthy()
  })

  it("never treats a partial projection as proof of absence", async () => {
    dossierApi.getRentalItemDossierPage.mockResolvedValue(
      page({ visibility: "PARTIAL" })
    )
    renderEvidence()

    expect(await screen.findAllByText("Нет подтверждения")).toHaveLength(2)
    expect(screen.queryByText("Подтверждённо отсутствует")).toBeNull()
    expect(
      screen.getAllByText(/Проекция неполная или недоступна/)
    ).toHaveLength(2)
  })

  it("reports unavailable dossier evidence without inventing an absence", async () => {
    dossierApi.getRentalItemDossierPage.mockRejectedValue(
      new Error("dossier unavailable")
    )
    renderEvidence()

    expect(
      await screen.findByText(
        "Досье недоступно: нет подтверждения наличия или отсутствия сметы и ремонта."
      )
    ).toBeTruthy()
    expect(screen.queryByText("Подтверждённо отсутствует")).toBeNull()
  })

  it("uses the chronologically latest actual return across differing offsets", async () => {
    renderEvidence([
      movement(
        "66666666-6666-4666-8666-666666666666",
        "RETURN",
        "2026-08-09T10:00:00+03:00"
      ),
      movement(
        "77777777-7777-4777-8777-777777777777",
        "RETURN",
        "2026-08-09T08:30:00Z"
      ),
    ])

    await waitFor(() =>
      expect(dossierApi.getRentalItemDossierPage).toHaveBeenCalledWith(
        "token",
        CABIN_ID,
        expect.objectContaining({ occurredFrom: "2026-08-09T08:30:00Z" })
      )
    )
    expect(screen.getByText(/\(фактический возврат\)/)).toBeTruthy()
  })

  it("labels the order-created lower bound honestly when no actual return exists", async () => {
    renderEvidence([
      movement("88888888-8888-4888-8888-888888888888", "RETURN", null),
    ])

    await waitFor(() =>
      expect(dossierApi.getRentalItemDossierPage).toHaveBeenCalledWith(
        "token",
        CABIN_ID,
        expect.objectContaining({ occurredFrom: ORDER_CREATED_AT })
      )
    )
    expect(screen.getByText(/\(создание заказа\)/)).toBeTruthy()
  })
})
