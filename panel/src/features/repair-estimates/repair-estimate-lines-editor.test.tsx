import { useState } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest"

const listQueues = vi.hoisted(() => vi.fn())

vi.mock("@/features/settings/task-board/api/task-board-settings-api", () => ({
  taskBoardSettingsClient: { listQueues },
}))

import { RepairEstimateLinesEditor } from "@/features/repair-estimates/repair-estimate-lines-editor"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"

const warehouseId = "00000000-0000-4000-8000-000000000001"

function EditorHarness({
  initialLines = [],
  customWorkLinesOnly = true,
}: {
  initialLines?: RepairEstimateLineDto[]
  customWorkLinesOnly?: boolean
}) {
  const [lines, setLines] = useState<RepairEstimateLineDto[]>(initialLines)
  return (
    <>
      <RepairEstimateLinesEditor
        lines={lines}
        readOnly={false}
        customWorkLinesOnly={customWorkLinesOnly}
        accessToken="panel-token"
        warehouseId={warehouseId}
        onChange={setLines}
      />
      <output data-testid="lines">{JSON.stringify(lines)}</output>
    </>
  )
}

function queues() {
  return [
    {
      id: "queue-repair",
      definitionId: "queue-definition-repair",
      name: "Кузовной ремонт",
      type: "REPAIR",
      active: true,
      hidden: false,
      sortOrder: 20,
    },
    {
      id: "queue-holding",
      definitionId: "queue-definition-holding",
      name: "Ожидание проверки",
      type: "HOLDING",
      active: true,
      hidden: false,
      sortOrder: 10,
    },
    {
      id: "queue-movement",
      definitionId: "queue-definition-movement",
      name: "Перемещение на ремонт",
      type: "MOVEMENT",
      active: true,
      hidden: false,
      sortOrder: 30,
    },
    {
      id: "queue-furniture",
      definitionId: "queue-definition-furniture",
      name: "Перемещение мебели",
      type: "FURNITURE_MOVEMENT",
      active: true,
      hidden: false,
      sortOrder: 40,
    },
    {
      id: "queue-hidden",
      definitionId: "queue-definition-hidden",
      name: "Скрытая очередь",
      type: "REPAIR",
      active: true,
      hidden: true,
      sortOrder: 50,
    },
    {
      id: "queue-inactive",
      definitionId: "queue-definition-inactive",
      name: "Неактивная очередь",
      type: "REPAIR",
      active: false,
      hidden: false,
      sortOrder: 60,
    },
  ]
}

function renderEditor(props: Parameters<typeof EditorHarness>[0] = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <EditorHarness {...props} />
    </QueryClientProvider>
  )
}

async function selectHoldingQueue(user: ReturnType<typeof userEvent.setup>) {
  await user.click(
    screen.getByRole("combobox", {
      name: "Рабочий этап пользовательской строки",
    })
  )
  expect(
    await screen.findByRole("option", { name: /Ожидание проверки/ })
  ).toBeTruthy()
  expect(screen.getByRole("option", { name: /Кузовной ремонт/ })).toBeTruthy()
  expect(
    screen.queryByRole("option", { name: /Перемещение на ремонт/ })
  ).toBeNull()
  expect(
    screen.queryByRole("option", { name: /Перемещение мебели/ })
  ).toBeNull()
  expect(screen.queryByRole("option", { name: /Скрытая очередь/ })).toBeNull()
  expect(
    screen.queryByRole("option", { name: /Неактивная очередь/ })
  ).toBeNull()
  await user.click(screen.getByRole("option", { name: /Ожидание проверки/ }))
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, "hasPointerCapture", {
    configurable: true,
    value: () => false,
  })
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
    configurable: true,
    value: () => undefined,
  })
})

describe("custom repair lines", () => {
  it("shows add or edit photo only for work lines and opens the work manager", async () => {
    const user = userEvent.setup()
    renderEditor({
      initialLines: [
        {
          id: "work-without-photo",
          sourceLineKey: "work-without-photo",
          lineType: "WORK",
          description: "Работа без фото",
          lineComment: "",
          unit: "шт.",
          quantity: 1,
          normativeMinutes: 30,
          unitPrice: "100.00",
          lineTotal: "100.00",
          catalogSnapshot: {
            nodeId: "work-without-photo",
            name: "Работа без фото",
            nodeType: "WORK",
            furnitureEquipment: null,
            characteristic: null,
          },
        },
        {
          id: "work-with-photo",
          sourceLineKey: "work-with-photo",
          lineType: "WORK",
          description: "Работа с фото",
          lineComment: "",
          unit: "шт.",
          quantity: 1,
          normativeMinutes: 30,
          unitPrice: "100.00",
          lineTotal: "100.00",
          maintenanceMediaReferences: [
            { mediaId: "work-photo", generation: 1 },
          ],
          catalogSnapshot: {
            nodeId: "work-with-photo",
            name: "Работа с фото",
            nodeType: "WORK",
            furnitureEquipment: null,
            characteristic: null,
          },
        },
        {
          id: "material",
          sourceLineKey: "material",
          lineType: "MATERIAL",
          description: "Материал",
          lineComment: "",
          unit: "шт.",
          quantity: 1,
          normativeMinutes: 0,
          unitPrice: "50.00",
          lineTotal: "50.00",
          catalogSnapshot: {
            nodeId: "material",
            name: "Материал",
            nodeType: "MATERIAL",
            furnitureEquipment: null,
            characteristic: null,
          },
        },
      ],
    })

    expect(
      screen.getAllByRole("button", { name: "Добавить фото" })
    ).toHaveLength(1)
    expect(
      screen.getAllByRole("button", { name: "Редактировать фото" })
    ).toHaveLength(1)

    await user.click(screen.getByRole("button", { name: "Добавить фото" }))
    expect(
      screen.getByRole("dialog", { name: "Фотографии работы" })
    ).toBeTruthy()
  })

  it("creates a WORK custom line in a dialog and binds it only to an active repair stage", async () => {
    listQueues.mockResolvedValue(queues())
    const user = userEvent.setup()

    renderEditor()

    await user.click(
      screen.getByRole("button", { name: "Добавить пользовательскую строку" })
    )

    expect(
      screen.getByRole("heading", { name: "Пользовательская строка" })
    ).toBeTruthy()
    expect(
      screen.getByRole("combobox", { name: "Тип пользовательской строки" })
        .textContent
    ).toContain("Работа")
    const duration = screen.getByLabelText("Время, мин")
    expect(duration.getAttribute("required")).not.toBeNull()
    expect(duration.getAttribute("min")).toBe("1")
    expect((duration as HTMLInputElement).value).toBe("")

    await user.type(
      screen.getByLabelText("Наименование пользовательской строки"),
      "Подтянуть крепления"
    )
    await user.type(duration, "45")
    expect(listQueues).toHaveBeenCalledWith("panel-token", warehouseId)
    await selectHoldingQueue(user)
    await user.click(screen.getByRole("button", { name: "Сохранить строку" }))

    expect(JSON.parse(screen.getByTestId("lines").textContent ?? "[]")).toEqual(
      [
        expect.objectContaining({
          lineType: "WORK",
          description: "Подтянуть крепления",
          catalogSnapshot: null,
          normativeMinutes: 45,
          customQueueBinding: {
            queueId: "queue-definition-holding",
            queueName: "Ожидание проверки",
            queueKind: "HOLDING",
          },
        }),
      ]
    )
    expect(screen.getByText("Подтянуть крепления")).toBeTruthy()
    expect(screen.getByText(/Итого 0,00/)).toBeTruthy()
  })

  it("creates a MATERIAL custom line with a zero duration and the chosen work stage", async () => {
    listQueues.mockResolvedValue(queues())
    const user = userEvent.setup()

    renderEditor()
    await user.click(
      screen.getByRole("button", { name: "Добавить пользовательскую строку" })
    )
    await user.click(
      screen.getByRole("combobox", { name: "Тип пользовательской строки" })
    )
    await user.click(screen.getByRole("option", { name: "Материал" }))

    expect(screen.queryByLabelText("Время, мин")).toBeNull()
    await user.type(
      screen.getByLabelText("Наименование пользовательской строки"),
      "Саморезы"
    )
    await selectHoldingQueue(user)
    await user.click(screen.getByRole("button", { name: "Сохранить строку" }))

    expect(JSON.parse(screen.getByTestId("lines").textContent ?? "[]")).toEqual(
      [
        expect.objectContaining({
          lineType: "MATERIAL",
          description: "Саморезы",
          normativeMinutes: 0,
          customQueueBinding: {
            queueId: "queue-definition-holding",
            queueName: "Ожидание проверки",
            queueKind: "HOLDING",
          },
        }),
      ]
    )
  })

  it("keeps catalog duration outside the custom-line dialog", () => {
    renderEditor({
      initialLines: [
        {
          id: "catalog-work-1",
          sourceLineKey: "catalog-work-1",
          lineType: "WORK",
          description: "Каталожная работа",
          lineComment: "",
          unit: "шт.",
          quantity: 1,
          normativeMinutes: 60,
          unitPrice: "100.00",
          lineTotal: "100.00",
          catalogSnapshot: {
            nodeId: "catalog-work-1",
            name: "Каталожная работа",
            nodeType: "WORK",
            furnitureEquipment: null,
            characteristic: null,
          },
        },
      ],
    })

    expect(screen.queryByLabelText("Время, мин")).toBeNull()
    expect(
      (screen.getByLabelText("Тип строки 1") as HTMLInputElement).value
    ).toBe("Работа")
  })

  it("edits the required duration of a manual work line outside stage-bound mode", async () => {
    const user = userEvent.setup()
    renderEditor({
      customWorkLinesOnly: false,
      initialLines: [
        {
          id: "manual-work-1",
          sourceLineKey: "manual-work-1",
          lineType: "WORK",
          description: "Ручная работа",
          lineComment: "",
          unit: "ед",
          quantity: 1,
          normativeMinutes: 30,
          unitPrice: "0.00",
          lineTotal: "0.00",
          catalogSnapshot: null,
        },
      ],
    })

    await user.click(screen.getByRole("button", { name: "Изменить" }))
    const duration = screen.getByLabelText("Время, мин") as HTMLInputElement
    expect(duration.value).toBe("30")

    await user.clear(duration)
    await user.type(duration, "75")
    await user.click(screen.getByRole("button", { name: "Сохранить строку" }))

    expect(JSON.parse(screen.getByTestId("lines").textContent ?? "[]")).toEqual(
      [expect.objectContaining({ normativeMinutes: 75 })]
    )
  })
})
