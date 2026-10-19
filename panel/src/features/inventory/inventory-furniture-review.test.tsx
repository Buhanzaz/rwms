import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import { InventoryFurnitureReview } from "@/features/inventory/inventory-furniture-review"
import type { InventoryFurnitureReviewDto } from "@/features/inventory/model/inventory"

const review: InventoryFurnitureReviewDto = {
  inventoryId: "11111111-1111-4111-8111-111111111111",
  sessionRevision: 5,
  stage: "FURNITURE",
  assetSnapshotSha256: "a".repeat(64),
  reviewSha256: null,
  confirmed: false,
  items: [
    {
      equipmentId: "22222222-2222-4222-8222-222222222222",
      catalogVersion: 333,
      equipmentName: "Стол",
      currentStockQuantity: 8,
      observedStockQuantity: 7,
      cabins: [
        {
          findingId: "44444444-4444-4444-8444-444444444444",
          assetId: "55555555-5555-4555-8555-555555555555",
          cabinNumber: "БЫТ-001",
          status: "WAREHOUSE",
          currentQuantity: 1,
          observedQuantity: 1,
        },
      ],
    },
  ],
}

describe("InventoryFurnitureReview", () => {
  afterEach(cleanup)

  it("keeps compact furniture aggregates, allows zero in cabins, and saves one full review", async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    const onDraftChange = vi.fn()
    render(
      <InventoryFurnitureReview
        review={review}
        pending={false}
        error={null}
        onSave={onSave}
        onDraftChange={onDraftChange}
      />
    )

    expect(screen.getByText("Текущий остаток")).toBeTruthy()
    expect(screen.getByText("В бытовках: 1 шт.")).toBeTruthy()
    const stock = screen.getByLabelText(
      "Посчитано на складе"
    ) as HTMLInputElement
    expect(stock.value).toBe("7")

    await user.click(screen.getByText("Бытовки (1)"))
    const cabin = screen.getByLabelText("Стало") as HTMLInputElement
    await user.clear(cabin)
    await user.type(cabin, "0")
    await user.clear(stock)
    await user.type(stock, "6")

    expect(screen.getByText("В бытовках: 0 шт.")).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Сохранить сверку мебели" })
    )

    expect(onSave).toHaveBeenCalledWith({
      ...review,
      items: [
        {
          ...review.items[0],
          observedStockQuantity: 6,
          cabins: [{ ...review.items[0].cabins[0], observedQuantity: 0 }],
        },
      ],
    })
  })

  it("does not submit a non-integer quantity and keeps a confirmed review editable", async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    const onDraftChange = vi.fn()
    const { rerender } = render(
      <InventoryFurnitureReview
        review={review}
        pending={false}
        error={null}
        onSave={onSave}
        onDraftChange={onDraftChange}
      />
    )

    const stock = screen.getByLabelText("Посчитано на складе")
    await user.clear(stock)
    await user.type(stock, "1.5")
    expect(
      (
        screen.getByRole("button", {
          name: "Сохранить сверку мебели",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    expect(onSave).not.toHaveBeenCalled()

    rerender(
      <InventoryFurnitureReview
        review={{ ...review, confirmed: true, reviewSha256: "b".repeat(64) }}
        pending={false}
        error={null}
        onSave={onSave}
        onDraftChange={onDraftChange}
      />
    )

    expect(screen.getByText("Сверка сохранена")).toBeTruthy()
    expect(
      (
        screen.getByRole("button", {
          name: "Сохранить изменения сверки",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(true)
    expect(
      (screen.getByLabelText("Посчитано на складе") as HTMLInputElement)
        .disabled
    ).toBe(false)

    const confirmedStock = screen.getByLabelText(
      "Посчитано на складе"
    ) as HTMLInputElement
    await user.clear(confirmedStock)
    await user.type(confirmedStock, "6")
    expect(
      (
        screen.getByRole("button", {
          name: "Сохранить изменения сверки",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(false)
    expect(onDraftChange).toHaveBeenLastCalledWith(true)
  })

  it("allows an empty catalog review to be confirmed", async () => {
    const onSave = vi.fn()
    render(
      <InventoryFurnitureReview
        review={{ ...review, items: [] }}
        pending={false}
        error={null}
        onSave={onSave}
        onDraftChange={vi.fn()}
      />
    )

    expect(
      screen.getByText(
        "В снимке инвентаризации нет мебели для сверки. Сохраните пустую сверку, чтобы подтвердить этот результат."
      )
    ).toBeTruthy()
    expect(
      (
        screen.getByRole("button", {
          name: "Сохранить сверку мебели",
        }) as HTMLButtonElement
      ).disabled
    ).toBe(false)
  })
})
