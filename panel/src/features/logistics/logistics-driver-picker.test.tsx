import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest"

const driverDirectoryApi = vi.hoisted(() => ({
  listRepairWorkerGroups: vi.fn(),
}))

vi.mock("@/features/repair-tasks/api/repair-worker-directory-api", () => ({
  repairWorkerGroupsQueryKey: (query: unknown) => [
    "repair-worker-groups",
    query,
  ],
  listRepairWorkerGroups: driverDirectoryApi.listRepairWorkerGroups,
}))

import { LogisticsDriverPicker } from "@/features/logistics/logistics-driver-picker"

const WAREHOUSE_ID = "11111111-1111-4111-8111-111111111111"
const DRIVER = {
  id: "22222222-2222-4222-8222-222222222222",
  name: "Иванов Иван",
}

const pointerCaptureDescriptors = new Map(
  [
    "hasPointerCapture",
    "setPointerCapture",
    "releasePointerCapture",
    "scrollIntoView",
  ].map((name) => [
    name,
    Object.getOwnPropertyDescriptor(HTMLElement.prototype, name),
  ])
)

beforeAll(() => {
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: {
      configurable: true,
      value: () => false,
    },
    setPointerCapture: {
      configurable: true,
      value: () => undefined,
    },
    releasePointerCapture: {
      configurable: true,
      value: () => undefined,
    },
    scrollIntoView: {
      configurable: true,
      value: () => undefined,
    },
  })
})

afterAll(() => {
  for (const [name, descriptor] of pointerCaptureDescriptors) {
    if (descriptor) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, name)
    }
  }
})

function renderPicker(onChange = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <LogisticsDriverPicker
        accessToken="driver-token"
        id="shipment-driver"
        warehouseId={WAREHOUSE_ID}
        value={null}
        onChange={onChange}
      />
    </QueryClientProvider>
  )
  return onChange
}

beforeEach(() => {
  driverDirectoryApi.listRepairWorkerGroups.mockResolvedValue([
    {
      id: "33333333-3333-4333-8333-333333333333",
      warehouseId: WAREHOUSE_ID,
      name: "Водители",
      active: true,
      queueIds: [],
      routeQueueKinds: ["MOVEMENT"],
      members: [DRIVER],
    },
  ])
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe("LogisticsDriverPicker", () => {
  it("uses the configured movement driver directory", async () => {
    const onChange = renderPicker()

    await waitFor(() =>
      expect(driverDirectoryApi.listRepairWorkerGroups).toHaveBeenCalledWith(
        {
          warehouseId: WAREHOUSE_ID,
          queueId: null,
          routeQueueKind: "MOVEMENT",
          purpose: "DRIVER_DIRECTORY",
        },
        "driver-token"
      )
    )

    await waitFor(() =>
      expect(
        (
          screen.getByRole("combobox", {
            name: "Водитель",
          }) as HTMLButtonElement
        ).disabled
      ).toBe(false)
    )

    const user = userEvent.setup()
    await user.click(screen.getByRole("combobox", { name: "Водитель" }))
    await user.click(await screen.findByRole("option", { name: DRIVER.name }))

    expect(onChange).toHaveBeenCalledWith(DRIVER)
  })
})
