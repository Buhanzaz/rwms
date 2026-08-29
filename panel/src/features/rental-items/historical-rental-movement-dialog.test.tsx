import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { OrderClientChoice } from "@/features/orders/components/order-client-chooser"

const clientsApi = vi.hoisted(() => ({
  createClient: vi.fn(),
  getClient: vi.fn(),
  createClientIdempotencyKey: vi.fn(
    () => "11111111-1111-4111-8111-111111111111"
  ),
}))
const movementsApi = vi.hoisted(() => ({
  createHistoricalRentalMovement: vi.fn(),
  updateHistoricalRentalShipment: vi.fn(),
  createHistoricalRentalMovementIdempotencyKey: vi.fn(
    () => "22222222-2222-4222-8222-222222222222"
  ),
}))
const toast = vi.hoisted(() => ({ success: vi.fn() }))

vi.mock("@/features/clients/api/clients-api", () => ({
  CLIENTS_QUERY_KEY: ["rental-clients"],
  ...clientsApi,
}))
vi.mock("@/features/rental-items/historical-rental-movement-api", () => ({
  ...movementsApi,
}))
vi.mock("sonner", () => ({ toast }))
vi.mock("@/features/orders/components/order-client-chooser", () => ({
  OrderClientChooser: ({
    onChange,
  }: {
    onChange: (choice: OrderClientChoice) => void
  }) => (
    <div>
      <button
        type="button"
        onClick={() =>
          onChange({
            kind: "existing",
            client: {
              id: "33333333-3333-4333-8333-333333333333",
              type: "LEGAL_ENTITY",
              displayName: "ООО Петров",
              phone: "+79990000000",
              contactPerson: "Пётр Петров",
              email: null,
              responsibleManagerId: "44444444-4444-4444-8444-444444444444",
              responsibleManagerDisplayName: "Менеджер",
              comment: null,
              source: null,
              additionalContacts: [],
              version: 1,
              createdAt: "2026-08-01T00:00:00Z",
              updatedAt: "2026-08-01T00:00:00Z",
            },
          })
        }
      >
        Выбрать существующего клиента
      </button>
      <button
        type="button"
        onClick={() =>
          onChange({
            kind: "new",
            clientType: "LEGAL_ENTITY",
            displayName: "ООО Новый",
            phone: "+79990000000",
            contactPerson: "Анна",
            email: null,
            comment: null,
            source: null,
            additionalContacts: [],
          })
        }
      >
        Создать нового клиента
      </button>
    </div>
  ),
}))
vi.mock("@/features/logistics/logistics-driver-picker", () => ({
  LogisticsDriverPicker: ({
    id,
    value,
    onChange,
  }: {
    id: string
    value: { id: string; name: string } | null
    onChange: (value: { id: string; name: string } | null) => void
  }) => (
    <label htmlFor={id}>
      Водитель
      <select
        id={id}
        value={value?.id ?? "unknown"}
        onChange={(event) =>
          onChange(
            event.target.value === "unknown"
              ? null
              : {
                  id: event.target.value,
                  name: "Иванов Иван",
                }
          )
        }
      >
        <option value="unknown">Неизвестен</option>
        <option value="dddddddd-dddd-4ddd-8ddd-dddddddddddd">
          Иванов Иван
        </option>
      </select>
    </label>
  ),
}))

import { HistoricalRentalMovementDialog } from "@/features/rental-items/historical-rental-movement-dialog"

const rentalItem = {
  id: "55555555-5555-4555-8555-555555555555",
  version: 9,
  warehouseId: "66666666-6666-4666-8666-666666666666",
  number: "210560",
  rentalTypeId: "77777777-7777-4777-8777-777777777777",
  dimensionId: "88888888-8888-4888-8888-888888888888",
  finishingId: "99999999-9999-4999-8999-999999999999",
  type: "БК-1",
  dimensions: null,
  finishing: null,
  category: null,
  characteristics: [],
  linoleum: null,
  status: "REPAIR" as const,
  comment: null,
  contents: null,
  contentsItems: [],
  shipmentDate: null,
  tenant: null,
  price: null,
  passport: {},
  tags: [],
}

function renderDialog(
  kind: "SHIPMENT" | "RETURN",
  existingShipment: {
    id: string
    version: number
    clientId: string | null
    driverSnapshot: string | null
    driverWorkerId: string | null
    scheduledDate: string | null
  } | null = null
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const onCreated = vi.fn()
  return {
    onCreated,
    ...render(
      <QueryClientProvider client={queryClient}>
        <HistoricalRentalMovementDialog
          open
          kind={kind}
          rentalItem={rentalItem}
          accessToken="access-token"
          actorId="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
          responsibleManagerDisplayName="Мария Менеджер"
          existingShipment={existingShipment}
          onOpenChange={vi.fn()}
          onCreated={onCreated}
        />
      </QueryClientProvider>
    ),
  }
}

beforeEach(() => {
  clientsApi.getClient.mockResolvedValue({
    id: "33333333-3333-4333-8333-333333333333",
    type: "LEGAL_ENTITY",
    displayName: "ООО Петров",
    phone: "+79990000000",
    contactPerson: "Пётр Петров",
    email: null,
    responsibleManagerId: "44444444-4444-4444-8444-444444444444",
    responsibleManagerDisplayName: "Менеджер",
    comment: null,
    source: null,
    additionalContacts: [],
    version: 1,
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
  })
  clientsApi.createClient.mockResolvedValue({
    id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  })
  movementsApi.createHistoricalRentalMovement.mockResolvedValue({
    id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    version: 3,
  })
  movementsApi.updateHistoricalRentalShipment.mockResolvedValue({
    id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    version: 6,
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("HistoricalRentalMovementDialog", () => {
  it("creates a fenced historical shipment with an explicit unknown driver", async () => {
    const user = userEvent.setup()
    const { onCreated } = renderDialog("SHIPMENT")

    expect(
      screen.getByText("Автоматически закрыто в связи с отгрузкой.", {
        exact: false,
      })
    ).toBeTruthy()
    await user.click(
      await screen.findByRole("button", {
        name: "Выбрать существующего клиента",
      })
    )
    fireEvent.change(screen.getByLabelText("Дата отгрузки"), {
      target: { value: "2026-08-02" },
    })
    await user.click(screen.getByRole("button", { name: "Создать отгрузку" }))

    await waitFor(() =>
      expect(movementsApi.createHistoricalRentalMovement).toHaveBeenCalledWith({
        accessToken: "access-token",
        idempotencyKey: "22222222-2222-4222-8222-222222222222",
        input: {
          warehouseId: rentalItem.warehouseId,
          rentalItemId: rentalItem.id,
          expectedRentalItemVersion: rentalItem.version,
          clientId: "33333333-3333-4333-8333-333333333333",
          driverSnapshot: null,
          driverWorkerId: null,
          kind: "SHIPMENT",
          occurredOn: "2026-08-02",
        },
      })
    )
    expect(clientsApi.createClient).not.toHaveBeenCalled()
    await waitFor(() =>
      expect(onCreated).toHaveBeenCalledWith({
        id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
        version: 3,
      })
    )
  })

  it("binds a selected driver without requiring route planning", async () => {
    const user = userEvent.setup()
    renderDialog("SHIPMENT")

    await user.click(
      screen.getByRole("button", { name: "Выбрать существующего клиента" })
    )
    await user.selectOptions(
      screen.getByRole("combobox", { name: "Водитель" }),
      "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    )
    await user.click(screen.getByRole("button", { name: "Создать отгрузку" }))

    await waitFor(() =>
      expect(movementsApi.createHistoricalRentalMovement).toHaveBeenCalledWith(
        expect.objectContaining({
          input: expect.objectContaining({
            driverSnapshot: "Иванов Иван",
            driverWorkerId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
          }),
        })
      )
    )
  })

  it("creates a new client first, then uses its server identity for a return", async () => {
    const user = userEvent.setup()
    renderDialog("RETURN")

    expect(
      screen.getByText("Возврат поступит в обычный процесс приёмки:", {
        exact: false,
      })
    ).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Создать нового клиента" })
    )
    fireEvent.change(screen.getByLabelText("Дата возврата"), {
      target: { value: "2026-08-03" },
    })
    await user.click(screen.getByRole("button", { name: "Создать возврат" }))

    await waitFor(() =>
      expect(clientsApi.createClient).toHaveBeenCalledTimes(1)
    )
    expect(clientsApi.createClient).toHaveBeenCalledWith({
      accessToken: "access-token",
      idempotencyKey: "11111111-1111-4111-8111-111111111111",
      input: {
        clientType: "LEGAL_ENTITY",
        displayName: "ООО Новый",
        phone: "+79990000000",
        contactPerson: "Анна",
        email: null,
        comment: null,
        source: null,
        additionalContacts: [],
      },
    })
    await waitFor(() =>
      expect(movementsApi.createHistoricalRentalMovement).toHaveBeenCalledWith(
        expect.objectContaining({
          input: expect.objectContaining({
            clientId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            kind: "RETURN",
            occurredOn: "2026-08-03",
          }),
        })
      )
    )
  })

  it("edits the existing historical shipment instead of creating a duplicate", async () => {
    const user = userEvent.setup()
    renderDialog("SHIPMENT", {
      id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
      version: 5,
      clientId: "33333333-3333-4333-8333-333333333333",
      driverSnapshot: "Иванов Иван",
      driverWorkerId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
      scheduledDate: "2026-08-01",
    })

    expect(
      await screen.findByRole("heading", {
        name: "Изменить отгрузку задним числом",
      })
    ).toBeTruthy()
    await user.click(
      await screen.findByRole("button", {
        name: "Выбрать существующего клиента",
      })
    )
    fireEvent.change(screen.getByLabelText("Дата отгрузки"), {
      target: { value: "2026-07-31" },
    })
    await user.click(screen.getByRole("button", { name: "Сохранить отгрузку" }))

    await waitFor(() =>
      expect(movementsApi.updateHistoricalRentalShipment).toHaveBeenCalledWith({
        accessToken: "access-token",
        documentId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
        idempotencyKey: "22222222-2222-4222-8222-222222222222",
        input: {
          expectedVersion: 5,
          rentalItemId: rentalItem.id,
          clientId: "33333333-3333-4333-8333-333333333333",
          driverSnapshot: "Иванов Иван",
          driverWorkerId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
          occurredOn: "2026-07-31",
        },
      })
    )
    expect(movementsApi.createHistoricalRentalMovement).not.toHaveBeenCalled()
  })
})
