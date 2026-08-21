import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import {
  afterAll,
  afterEach,
  beforeAll,
  describe,
  expect,
  it,
  vi,
} from "vitest"

import {
  GroupAvailabilityDialog,
  GroupEditorDialog,
  QueueDefinitionEditorDialog,
  WorkerEditorDialog,
} from "@/features/settings/task-board/settings-editor-dialogs"
import type {
  QueueDefinitionDto,
  WorkerClassDto,
  WorkerDto,
  WorkerGroupDto,
} from "@/features/settings/task-board/model/task-board-settings"

function workerClass(
  id: string,
  name: string,
  logisticsPrimary = false
): WorkerClassDto {
  return {
    id,
    version: 1,
    name,
    description: null,
    comment: null,
    sortOrder: 1,
    active: true,
    logisticsPrimary,
  }
}

const driverClass = workerClass("driver", "Водители", true)
const slingerClass = workerClass("slinger", "Стропальщики")
const generalClass = workerClass("general", "Разнорабочие")
const movementDefinition: QueueDefinitionDto = {
  id: "definition-movement",
  version: 2,
  name: "Внутренние работы",
  description: null,
  type: "REPAIR",
  purpose: "GENERAL",
  sortOrder: 1,
  active: true,
  hidden: false,
  collapsed: false,
  holdingPeriodMinutes: null,
  notificationThreshold: null,
  notifyWhenThresholdReached: false,
  resultPhotoMinCount: 1,
  availableTaskLimit: 6,
  bindings: [
    {
      id: "binding-driver",
      version: 3,
      workerClass: driverClass,
      order: 0,
      primary: true,
      stopTaskOnTake: false,
      participationPolicy: "PRIMARY",
      notifyOnPrimaryTake: false,
    },
    {
      id: "binding-slinger",
      version: 3,
      workerClass: slingerClass,
      order: 1,
      primary: false,
      stopTaskOnTake: true,
      participationPolicy: "REQUIRED",
      notifyOnPrimaryTake: true,
    },
  ],
}

function worker(
  id: string,
  displayName: string,
  classes: WorkerClassDto[],
  active = true
): WorkerDto {
  return {
    id,
    version: 1,
    warehouseId: "warehouse-1",
    displayName,
    firstName: null,
    lastName: null,
    middleName: null,
    active,
    comment: null,
    appLogin: null,
    credentialStatus: "NOT_CONFIGURED",
    credentialError: null,
    currentGroupId: null,
    currentGroupName: null,
    operationalAvailability: "AVAILABLE",
    qualifications: classes.map((qualificationClass, index) => ({
      id: `${id}-qualification-${index}`,
      version: 1,
      workerClass: qualificationClass,
      active: true,
      comment: null,
    })),
  }
}

function group(
  id: string,
  name: string,
  member: WorkerDto,
  {
    active = true,
    operationalStatus = "AVAILABLE",
  }: {
    active?: boolean
    operationalStatus?: "AVAILABLE" | "DISABLED"
  } = {}
): WorkerGroupDto {
  return {
    id,
    version: 3,
    warehouseId: "warehouse-1",
    workerClass: generalClass,
    name,
    description: null,
    active,
    operationalStatus,
    unavailableSince:
      operationalStatus === "DISABLED" ? "2026-07-30T08:00:00Z" : null,
    unavailabilityReason:
      operationalStatus === "DISABLED" ? "Пересменка" : null,
    members: [
      {
        id: `${id}-member`,
        version: 1,
        workerId: member.id,
        workerName: member.displayName,
        active: true,
      },
    ],
  }
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
  vi.stubGlobal(
    "ResizeObserver",
    class ResizeObserver {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
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
  vi.unstubAllGlobals()
  for (const [name, descriptor] of pointerCaptureDescriptors) {
    if (descriptor) {
      Object.defineProperty(HTMLElement.prototype, name, descriptor)
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, name)
    }
  }
})
afterEach(cleanup)

describe("QueueDefinitionEditorDialog", () => {
  it("owns the global queue settings and existing class bindings", async () => {
    const user = userEvent.setup()
    const onSave = vi.fn(async () => undefined)

    render(
      <QueueDefinitionEditorDialog
        definition={movementDefinition}
        classes={[driverClass, slingerClass]}
        pending={false}
        error={null}
        onClose={vi.fn()}
        onSave={onSave}
      />
    )

    const name = screen.getByRole("textbox", { name: "Название" })
    await user.clear(name)
    await user.type(name, "Водители")
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    expect(onSave).toHaveBeenCalledWith({
      version: movementDefinition.version,
      name: "Водители",
      description: null,
      type: "REPAIR",
      purpose: "GENERAL",
      sortOrder: movementDefinition.sortOrder,
      active: true,
      hidden: false,
      collapsed: false,
      holdingPeriodMinutes: null,
      notificationThreshold: null,
      notifyWhenThresholdReached: false,
      resultPhotoMinCount: 1,
      availableTaskLimit: 6,
      bindings: [
        {
          workerClassId: driverClass.id,
          order: 0,
          stopTaskOnTake: false,
          participationPolicy: "PRIMARY",
          notifyOnPrimaryTake: false,
        },
        {
          workerClassId: slingerClass.id,
          order: 1,
          stopTaskOnTake: true,
          participationPolicy: "REQUIRED",
          notifyOnPrimaryTake: true,
        },
      ],
    })
    expect(screen.queryByRole("textbox", { name: "Описание" })).toBeNull()
    await user.click(screen.getByRole("combobox", { name: "Тип" }))
    expect(screen.getByRole("option", { name: "Перемещение" })).toBeTruthy()
    await user.click(screen.getByRole("option", { name: "Ремонт" }))
    expect(
      screen.getByRole("spinbutton", { name: "Минимум фото результата" })
    ).toBeTruthy()
    expect(screen.getByText("Классы исполнителей")).toBeTruthy()
  })

  it("allows choosing and reordering global class bindings", async () => {
    const user = userEvent.setup()
    const onSave = vi.fn(async () => undefined)

    render(
      <QueueDefinitionEditorDialog
        definition={null}
        classes={[driverClass, slingerClass]}
        pending={false}
        error={null}
        onClose={vi.fn()}
        onSave={onSave}
      />
    )

    await user.type(screen.getByRole("textbox", { name: "Название" }), "Монтаж")
    await user.click(screen.getByRole("checkbox", { name: driverClass.name }))
    await user.click(screen.getByRole("checkbox", { name: slingerClass.name }))
    await user.click(screen.getAllByRole("button", { name: "Выше" })[1])
    await user.click(
      screen.getByRole("checkbox", {
        name: "Останавливать текущую работу при взятии",
      })
    )
    await user.click(
      screen.getByRole("checkbox", {
        name: "Уведомлять после принятия основным исполнителем",
      })
    )
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        version: 0,
        name: "Монтаж",
        sortOrder: 0,
        bindings: [
          {
            workerClassId: slingerClass.id,
            order: 0,
            stopTaskOnTake: false,
            participationPolicy: "PRIMARY",
            notifyOnPrimaryTake: false,
          },
          {
            workerClassId: driverClass.id,
            order: 1,
            stopTaskOnTake: true,
            participationPolicy: "OPTIONAL",
            notifyOnPrimaryTake: true,
          },
        ],
      })
    )
  })

  it("rejects a result photo minimum outside the supported range", () => {
    const onSave = vi.fn(async () => undefined)

    render(
      <QueueDefinitionEditorDialog
        definition={movementDefinition}
        classes={[driverClass, slingerClass]}
        pending={false}
        error={null}
        onClose={vi.fn()}
        onSave={onSave}
      />
    )

    fireEvent.change(
      screen.getByRole("spinbutton", {
        name: "Минимум фото результата",
      }),
      { target: { value: "21" } }
    )
    fireEvent.submit(
      screen.getByRole("button", { name: "Сохранить" }).closest("form")!
    )

    expect(
      screen.getByText(
        "Минимум фотографий должен быть целым числом от 0 до 20."
      )
    ).toBeTruthy()
    expect(onSave).not.toHaveBeenCalled()
  })
})

describe("WorkerEditorDialog", () => {
  it("preserves an existing qualification hidden from this settings section", async () => {
    const user = userEvent.setup()
    const onSave = vi.fn(async () => undefined)
    const driver = worker("driver-worker", "Алексей Водитель", [driverClass])

    render(
      <WorkerEditorDialog
        item={driver}
        classes={[generalClass]}
        pending={false}
        error={null}
        onClose={vi.fn()}
        onSave={onSave}
      />
    )

    expect(
      screen.queryByRole("checkbox", { name: driverClass.name })
    ).toBeNull()
    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        qualifications: [
          {
            workerClassId: driverClass.id,
            active: true,
            comment: null,
          },
        ],
      })
    )
  })
})

describe("GroupEditorDialog", () => {
  it("shows only workers qualified for the group class and has no role field", async () => {
    const user = userEvent.setup()
    const onSave = vi.fn(async () => undefined)
    const generalOnly = worker("general-worker", "Только разнорабочий", [
      generalClass,
    ])
    const multiQualified = worker("multi-worker", "Разнорабочий-стропальщик", [
      generalClass,
      slingerClass,
    ])
    const driver = worker("driver-worker", "Только водитель", [driverClass])
    const inactive = worker(
      "inactive-worker",
      "Неактивный разнорабочий",
      [generalClass],
      false
    )

    render(
      <GroupEditorDialog
        item={null}
        classes={[generalClass, slingerClass, driverClass]}
        workers={[generalOnly, multiQualified, driver, inactive]}
        pending={false}
        error={null}
        onClose={vi.fn()}
        onSave={onSave}
      />
    )

    expect(
      screen.getByRole("checkbox", { name: generalOnly.displayName })
    ).toBeTruthy()
    expect(
      screen.getByRole("checkbox", { name: multiQualified.displayName })
    ).toBeTruthy()
    expect(
      screen.queryByRole("checkbox", { name: driver.displayName })
    ).toBeNull()
    expect(
      screen.queryByRole("checkbox", { name: inactive.displayName })
    ).toBeNull()
    expect(screen.queryByPlaceholderText("Роль в бригаде")).toBeNull()

    await user.type(
      screen.getByRole("textbox", { name: "Название" }),
      "Стропальщики"
    )
    await user.click(
      screen.getByRole("checkbox", { name: generalOnly.displayName })
    )
    await user.click(
      screen.getByRole("checkbox", { name: multiQualified.displayName })
    )
    await user.click(screen.getByRole("combobox", { name: "Класс" }))
    await user.click(screen.getByRole("option", { name: slingerClass.name }))

    expect(
      screen.queryByRole("checkbox", { name: generalOnly.displayName })
    ).toBeNull()
    expect(
      screen
        .getByRole("checkbox", { name: multiQualified.displayName })
        .getAttribute("data-state")
    ).toBe("checked")

    await user.click(screen.getByRole("button", { name: "Сохранить" }))

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        workerClassId: slingerClass.id,
        members: [{ workerId: multiQualified.id, active: true }],
        currentGroupChanges: [
          {
            workerId: multiQualified.id,
            expectedVersion: multiQualified.version,
            current: true,
          },
        ],
      })
    )
  })
})

describe("GroupAvailabilityDialog", () => {
  it("requires and submits a compact unavailability reason", async () => {
    const user = userEvent.setup()
    const currentWorker = worker("worker-1", "Иван Петров", [generalClass])
    const currentGroup = group("group-1", "Бригада 1", currentWorker)
    const onSave = vi.fn(async () => undefined)

    render(
      <GroupAvailabilityDialog
        group={currentGroup}
        pending={false}
        error={null}
        onClose={vi.fn()}
        onSave={onSave}
      />
    )

    await user.click(screen.getByRole("button", { name: "Отключить" }))
    expect(screen.getByText("Укажите причину недоступности.")).toBeTruthy()
    expect(onSave).not.toHaveBeenCalled()

    await user.type(
      screen.getByRole("textbox", { name: "Причина" }),
      "Пересменка"
    )
    await user.click(screen.getByRole("button", { name: "Отключить" }))

    expect(onSave).toHaveBeenCalledWith("Пересменка")
  })
})
