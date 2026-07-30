import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import { useContext, useEffect, useState } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { WarehouseInfo } from "@/api/warehouse-api"
import { WarehouseContext } from "@/contexts/warehouse-context"
import { WarehouseProvider } from "@/contexts/warehouse-provider"

const { listWarehouses } = vi.hoisted(() => ({
  listWarehouses: vi.fn(),
}))

const auth = vi.hoisted(() => ({
  accessToken: "access-token" as string | null,
}))

vi.mock("@/api/warehouse-api", () => ({
  listWarehouses,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => ({ accessToken: auth.accessToken }),
}))

const warehouse: WarehouseInfo = {
  id: "00000000-0000-4000-8000-000000000001",
  version: 0,
  name: "Северный",
  city: "Санкт-Петербург",
  address: null,
  timeZone: "Europe/Moscow",
  active: true,
  sortOrder: null,
}

function deferred<T>() {
  let resolve: (value: T) => void
  let reject: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })

  return { promise, resolve: resolve!, reject: reject! }
}

function SelectionProbe() {
  const context = useContext(WarehouseContext)

  if (context === null) {
    throw new Error("WarehouseContext is unavailable")
  }

  return <output>{context.selectedWarehouseId ?? "none"}</output>
}

function ReloadProbe() {
  const context = useContext(WarehouseContext)

  if (context === null) {
    throw new Error("WarehouseContext is unavailable")
  }

  return (
    <>
      <button type="button" onClick={() => void context.reloadWarehouses()}>
        Обновить склады
      </button>
      <output aria-label="Ошибка складов">{context.error ?? "none"}</output>
    </>
  )
}

function LoadingGate({ onUnmount }: { onUnmount: () => void }) {
  const context = useContext(WarehouseContext)

  if (context === null) {
    throw new Error("WarehouseContext is unavailable")
  }

  if (context.isLoading) {
    return <output>loading</output>
  }

  return <DraftProbe onUnmount={onUnmount} />
}

function DraftProbe({ onUnmount }: { onUnmount: () => void }) {
  const [value, setValue] = useState("")

  useEffect(() => onUnmount, [onUnmount])

  return (
    <input
      aria-label="Черновик"
      value={value}
      onChange={(event) => setValue(event.target.value)}
    />
  )
}

afterEach(() => {
  cleanup()
  listWarehouses.mockReset()
  auth.accessToken = "access-token"
  window.localStorage.clear()
})

describe("WarehouseProvider", () => {
  it("waits for an access token and loads when authentication completes", async () => {
    auth.accessToken = null
    listWarehouses.mockResolvedValue([warehouse])

    const view = render(
      <WarehouseProvider>
        <SelectionProbe />
      </WarehouseProvider>
    )

    expect(listWarehouses).not.toHaveBeenCalled()
    expect(screen.getByText("none")).toBeTruthy()

    auth.accessToken = "access-token"
    view.rerender(
      <WarehouseProvider>
        <SelectionProbe />
      </WarehouseProvider>
    )

    await waitFor(() => expect(screen.getByText(warehouse.id)).toBeTruthy())

    expect(listWarehouses).toHaveBeenCalledTimes(1)
    expect(listWarehouses).toHaveBeenCalledWith("access-token")
  })

  it("loads through the authenticated API and replaces a stale mock selection", async () => {
    window.localStorage.setItem("wms:selected-warehouse-id", "spb")
    listWarehouses.mockResolvedValue([warehouse])

    render(
      <WarehouseProvider>
        <SelectionProbe />
      </WarehouseProvider>
    )

    await waitFor(() => expect(screen.getByText(warehouse.id)).toBeTruthy())

    expect(listWarehouses).toHaveBeenCalledWith("access-token")
    expect(window.localStorage.getItem("wms:selected-warehouse-id")).toBe(
      warehouse.id
    )
  })

  it("keeps the loaded application mounted when the access token rotates", async () => {
    listWarehouses.mockResolvedValue([warehouse])
    const unmounted = vi.fn()

    const view = render(
      <WarehouseProvider>
        <LoadingGate onUnmount={unmounted} />
      </WarehouseProvider>
    )

    const draft = await screen.findByRole("textbox", { name: "Черновик" })
    fireEvent.change(draft, { target: { value: "Несохранённые данные" } })

    auth.accessToken = "renewed-access-token"
    view.rerender(
      <WarehouseProvider>
        <LoadingGate onUnmount={unmounted} />
      </WarehouseProvider>
    )

    await waitFor(() =>
      expect(screen.getByDisplayValue("Несохранённые данные")).toBeTruthy()
    )
    expect(listWarehouses).toHaveBeenCalledTimes(1)
    expect(unmounted).not.toHaveBeenCalled()
  })

  it("retries the first load with a fresh token if the previous token expires", async () => {
    const firstRequest = deferred<WarehouseInfo[]>()
    listWarehouses
      .mockReturnValueOnce(firstRequest.promise)
      .mockResolvedValueOnce([warehouse])

    const view = render(
      <WarehouseProvider>
        <SelectionProbe />
      </WarehouseProvider>
    )

    await waitFor(() =>
      expect(listWarehouses).toHaveBeenCalledWith("access-token")
    )

    auth.accessToken = "renewed-access-token"
    view.rerender(
      <WarehouseProvider>
        <SelectionProbe />
      </WarehouseProvider>
    )
    firstRequest.reject(new Error("Срок действия токена истёк"))

    await waitFor(() => expect(screen.getByText(warehouse.id)).toBeTruthy())

    expect(listWarehouses).toHaveBeenCalledTimes(2)
    expect(listWarehouses).toHaveBeenLastCalledWith("renewed-access-token")
  })

  it("keeps the loaded application mounted when a manual refresh fails", async () => {
    listWarehouses.mockResolvedValueOnce([warehouse])
    const unmounted = vi.fn()

    render(
      <WarehouseProvider>
        <LoadingGate onUnmount={unmounted} />
        <ReloadProbe />
      </WarehouseProvider>
    )

    const draft = await screen.findByRole("textbox", { name: "Черновик" })
    fireEvent.change(draft, { target: { value: "Несохранённые данные" } })
    listWarehouses.mockRejectedValueOnce(new Error("Сервис складов недоступен"))

    fireEvent.click(screen.getByRole("button", { name: "Обновить склады" }))

    await waitFor(() => expect(listWarehouses).toHaveBeenCalledTimes(2))
    await waitFor(() =>
      expect(screen.getByDisplayValue("Несохранённые данные")).toBeTruthy()
    )

    expect(screen.getByLabelText("Ошибка складов").textContent).toBe("none")
    expect(unmounted).not.toHaveBeenCalled()
  })
})
