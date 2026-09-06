import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, cleanup, renderHook, waitFor } from "@testing-library/react"
import type { ReactNode } from "react"
import { afterEach, beforeEach, expect, it, vi } from "vitest"

import type { CreateEquipmentMovementTaskInput } from "@/features/logistics/api/equipment-movement-tasks-api"
import type { RentalItemContentsTransferRow } from "./rental-item-contents-transfer-support"
import {
  useContentsMovementMutation,
  useRentalItemContentsDraft,
} from "./use-rental-item-contents-movement"
import { ApiError } from "@/lib/api-client"

const api = vi.hoisted(() => ({
  create: vi.fn(),
  key: vi.fn(),
  success: vi.fn(),
}))
vi.mock("@/features/logistics/api/equipment-movement-tasks-api", () => ({
  createEquipmentMovementTask: api.create,
  createEquipmentMovementTaskIdempotencyKey: api.key,
}))
vi.mock("sonner", () => ({ toast: { success: api.success } }))

afterEach(cleanup)
beforeEach(() => {
  vi.clearAllMocks()
  api.key.mockReturnValueOnce("key-1").mockReturnValueOnce("key-2")
})

function row(
  id: string,
  quantity: number,
  version = 5
): RentalItemContentsTransferRow<"CABIN_NON_RENTED"> {
  return {
    equipmentId: id,
    name: id,
    availableQuantity: quantity,
    equipmentBalances: [],
    sourceBalance: {
      id: `balance-${id}`,
      version,
      equipmentId: id,
      warehouseId: "warehouse",
      rentalItemId: "source",
      locationKind: "CABIN_NON_RENTED",
      quantity,
      availableStock: quantity,
      activeHeldQuantity: 0,
    },
  }
}

it("deselects a zero quantity and selects all with restored positive quantities", () => {
  const onChange = vi.fn()
  const { result } = renderHook(() =>
    useRentalItemContentsDraft([row("table", 2), row("chair", 3)], onChange)
  )
  act(() => result.current.changeQuantity("table", -2))
  expect(result.current.rows[0]).toMatchObject({ quantity: 0, selected: false })
  expect(result.current.selectedRows).toHaveLength(0)
  act(() => result.current.toggleAll())
  expect(result.current.allSelected).toBe(true)
  expect(result.current.selectedRows.map((item) => item.quantity)).toEqual([
    2, 3,
  ])
  act(() => result.current.toggleAll())
  expect(result.current.selectedRows).toHaveLength(0)
  act(() => result.current.toggleRow("table", true))
  expect(result.current.selectedRows.map((item) => item.equipmentId)).toEqual([
    "table",
  ])
  expect(onChange).toHaveBeenCalledTimes(4)
})

it("uses refetched availability and version while preserving the local selection", () => {
  const { result, rerender } = renderHook(
    ({ rows }) => useRentalItemContentsDraft(rows, vi.fn()),
    {
      initialProps: { rows: [row("table", 5)] },
    }
  )
  act(() => result.current.changeQuantity("table", -1))
  rerender({ rows: [row("table", 2, 6)] })
  expect(result.current.selectedRows[0]).toMatchObject({
    quantity: 2,
    sourceBalance: { version: 6 },
  })
  act(() => result.current.changeQuantity("table", 99))
  expect(result.current.selectedRows[0].quantity).toBe(2)
  rerender({ rows: [] })
  expect(result.current.selectedRows).toHaveLength(0)
})

it("keeps a retry key after 409, invalidates affected reads and changes identity only with the input", async () => {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  const invalidate = vi.spyOn(queryClient, "invalidateQueries")
  const onClose = vi.fn()
  const onErrorText = vi.fn()
  const input: CreateEquipmentMovementTaskInput = {
    warehouseId: "warehouse",
    unitNumber: "БЫТ-1",
    plannedDurationMinutes: null,
    deadlineAt: "2030-07-21T09:00:00Z",
    lines: [
      {
        equipmentId: "table",
        sourceRentalItemId: "source",
        sourceLocationKind: "CABIN_NON_RENTED",
        expectedSourceBalanceVersion: 5,
        targetRentalItemId: null,
        targetLocationKind: "STOCK",
        quantity: 2,
      },
    ],
  }
  const conflict = new ApiError("Обновите остатки", 409, "VERSION_CONFLICT")
  api.create
    .mockRejectedValueOnce(conflict)
    .mockResolvedValue({ deadlineAt: input.deadlineAt })
  const { result, rerender } = renderHook(
    ({ value }) =>
      useContentsMovementMutation({
        accessToken: "token",
        createInput: () => value,
        onClose,
        onErrorText,
      }),
    {
      initialProps: { value: input },
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={queryClient}>
          {children}
        </QueryClientProvider>
      ),
    }
  )
  await act(async () => {
    await expect(result.current.mutation.mutateAsync()).rejects.toBe(conflict)
  })
  await waitFor(() =>
    expect(onErrorText).toHaveBeenCalledWith("Обновите остатки")
  )
  expect(invalidate).toHaveBeenCalled()
  expect(onClose).not.toHaveBeenCalled()
  await act(async () => {
    await result.current.mutation.mutateAsync()
  })
  expect(
    api.create.mock.calls.slice(0, 2).map(([request]) => request.idempotencyKey)
  ).toEqual(["key-1", "key-1"])
  expect(onClose).toHaveBeenCalledOnce()
  expect(api.success).toHaveBeenCalledOnce()
  expect(
    invalidate.mock.calls.every(([options]) => Array.isArray(options?.queryKey))
  ).toBe(true)

  rerender({
    value: {
      ...input,
      lines: [{ ...input.lines[0], expectedSourceBalanceVersion: 6 }],
    },
  })
  await act(async () => {
    await result.current.mutation.mutateAsync()
  })
  expect(api.create.mock.calls[2][0]).toMatchObject({
    idempotencyKey: "key-2",
    input: { lines: [{ expectedSourceBalanceVersion: 6 }] },
  })
  queryClient.clear()
})
