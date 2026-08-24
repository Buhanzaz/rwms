import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
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

const useServiceOwnerMedia = vi.hoisted(() => vi.fn())
const serviceOwnerPhotos = vi.hoisted(() => vi.fn())
const photoCarousel = vi.hoisted(() => vi.fn())

vi.mock("@/components/media/photo-carousel", () => ({
  PhotoCarousel: ({
    photos,
    ...props
  }: {
    photos: Array<{ id: string; url: string }>
    [key: string]: unknown
  }) => {
    photoCarousel({ photos, ...props })
    return (
      <div>
        {photos.map((photo, index) => (
          <img
            key={photo.id}
            src={photo.url}
            alt={`Фото ${index + 1} из ${photos.length}`}
          />
        ))}
      </div>
    )
  },
}))

vi.mock("@/features/media/service-owner-photos", () => ({
  ServiceOwnerPhotos: (props: unknown) => {
    serviceOwnerPhotos(props)
    return <div>Фото владельца</div>
  },
}))

vi.mock("@/features/media/use-service-owner-media", () => ({
  useServiceOwnerMedia,
}))

import { RepairAcceptanceDossier } from "@/features/acceptance/repair-acceptance-dossier"
import type { RepairTaskDto } from "@/features/repair-tasks/model/repair-task"

const warehouseId = "00000000-0000-4000-8000-000000000001"
const repairId = "00000000-0000-4000-8000-000000000002"
const rentalItemId = "00000000-0000-4000-8000-000000000003"
const stageId = "00000000-0000-4000-8000-000000000004"
const entryId = "00000000-0000-4000-8000-000000000005"
const evidenceId = "00000000-0000-4000-8000-000000000006"
const mediaId = "00000000-0000-4000-8000-000000000007"
const workerId = "00000000-0000-4000-8000-000000000008"
const workerGroupId = "00000000-0000-4000-8000-000000000009"
const workLineId = "00000000-0000-4000-8000-000000000010"
const sourceMediaId = "00000000-0000-4000-8000-000000000012"
const generalMediaId = "00000000-0000-4000-8000-000000000013"
const assignmentId = "00000000-0000-4000-8000-000000000014"
const materialLineId = "00000000-0000-4000-8000-000000000015"
const inventoryFindingId = "00000000-0000-4000-8000-000000000018"
const capturedAt = "2026-07-18T09:45:00Z"
const recordedAt = "2026-07-18T10:00:00Z"

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
  class ResizeObserverMock {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  class IntersectionObserverMock {
    observe() {}
    unobserve() {}
    disconnect() {}
  }

  vi.stubGlobal("ResizeObserver", ResizeObserverMock)
  vi.stubGlobal("IntersectionObserver", IntersectionObserverMock)
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {},
      dispatchEvent: () => false,
    }))
  )
  Object.defineProperties(HTMLElement.prototype, {
    hasPointerCapture: { configurable: true, value: () => false },
    setPointerCapture: { configurable: true, value: () => undefined },
    releasePointerCapture: { configurable: true, value: () => undefined },
    scrollIntoView: { configurable: true, value: () => undefined },
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
  vi.unstubAllGlobals()
})

const task: RepairTaskDto = {
  id: repairId,
  version: 4,
  status: "COMPLETED",
  kind: "REPAIR",
  origin: "DIRECT_REPAIR",
  acceptanceStatus: "PENDING",
  startedAt: "2026-07-18T08:00:00Z",
  completedAt: recordedAt,
  warehouseId,
  rentalItemId,
  cabinNumber: "БТ-42",
  actorId: "operator-1",
  sourceParty: "Склад",
  dispatchDate: "2026-07-18",
  maintenanceMediaReferences: [{ mediaId: generalMediaId, generation: 2 }],
  coverMediaId: generalMediaId,
  subtasks: [
    {
      id: stageId,
      taskBoardEntryId: entryId,
      kind: "REPAIR_WORK",
      status: "DONE",
      workLines: [
        {
          id: workLineId,
          sourceLineKey: workLineId,
          lineType: "WORK",
          description: "Заменить окно",
          lineComment: "Проверить герметичность",
          unit: "шт.",
          quantity: 1,
          unitPrice: "100.00",
          lineTotal: "100.00",
          catalogSnapshot: null,
          maintenanceMediaReferences: [
            { mediaId: sourceMediaId, generation: 3 },
          ],
        },
      ],
      materialLines: [
        {
          id: materialLineId,
          sourceLineKey: materialLineId,
          lineType: "MATERIAL",
          description: "ПВХ панель белая",
          lineComment: "",
          unit: "шт.",
          quantity: 2,
          unitPrice: "50.00",
          lineTotal: "100.00",
          catalogSnapshot: null,
          maintenanceMediaReferences: [],
        },
      ],
      primaryLineId: workLineId,
      groupComment: "Монтажная группа",
      evidence: [
        {
          evidenceId,
          entryId,
          workerId,
          workerDisplayName: "Иван Петров",
          workerGroupId,
          workerGroupName: "Бригада 1",
          mediaId,
          mediaGeneration: 4,
          capturedAt,
          recordedAt,
          state: "READY",
        },
      ],
      queueId: "00000000-0000-4000-8000-000000000011",
      queueName: "Внутренние работы",
      routeQueueKind: "REPAIR",
      sortOrder: 0,
      queuePosition: 0,
      plannedDurationMinutes: 30,
      startedAt: "2026-07-18T08:00:00Z",
      completedAt: recordedAt,
      activeStartedAt: null,
      activeWorkSeconds: 3600,
      workerGroup: { id: workerGroupId, name: "Бригада 1" },
      assignments: [
        {
          id: assignmentId,
          worker: { id: workerId, name: "Пётр Сидоров" },
          assignedAt: "2026-07-18T07:50:00Z",
          startedAt: "2026-07-18T08:00:00Z",
          pausedAt: null,
          finishedAt: recordedAt,
          activeStartedAt: null,
          status: "DONE",
        },
      ],
    },
  ],
  sourceEstimateId: null,
  sourceEstimateVersion: null,
  sourceInventoryId: null,
  sourceInventoryFindingId: null,
  sourceRepairTaskId: null,
  sourceRepairTaskVersion: null,
  readyAt: recordedAt,
  movementToRepair: false,
  logisticsPlanningMode: "AUTO",
  logisticsScheduledDate: null,
  createdAt: "2026-07-18T07:00:00Z",
  updatedAt: recordedAt,
}

function renderDossier(
  canEdit = false,
  dossierTask: RepairTaskDto = task,
  headerActionsContainer: HTMLElement | null = null,
  canManage = false
) {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <RepairAcceptanceDossier
          accessToken="token"
          task={dossierTask}
          mode="ACCEPTANCE"
          canEdit={canEdit}
          canManage={canManage}
          headerActionsContainer={headerActionsContainer}
        />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  useServiceOwnerMedia.mockReturnValue({
    assets: [
      {
        id: mediaId,
        kind: "IMAGE",
        status: "READY",
        generation: 4,
      },
    ],
    photos: [
      {
        id: mediaId,
        url: "blob:worker-result",
        variants: { medium: { url: "blob:worker-result" } },
      },
    ],
    query: {
      isLoading: false,
      isError: false,
      isSuccess: true,
    },
    requestFullscreen: vi.fn(),
    previewUnavailable: false,
  })
})

afterEach(cleanup)

describe("repair acceptance worker evidence", () => {
  it("shows one compact queue with exact worker, timing and line facts", () => {
    renderDossier()

    expect(screen.getByText("БТ-42")).toBeTruthy()
    expect(screen.getByText("Заменить окно — 1 шт.")).toBeTruthy()
    expect(screen.getByText("ПВХ панель белая — 2 шт.")).toBeTruthy()
    expect(screen.queryByText("Основная")).toBeNull()
    expect(screen.queryByText("Наименование")).toBeNull()
    expect(screen.queryByText("Количество")).toBeNull()
    expect(screen.queryByText("Проверить герметичность")).toBeNull()
    expect(screen.getByRole("button", { name: "Комментарий" })).toBeTruthy()
    expect(screen.queryByText("Подтверждение фото")).toBeNull()
    expect(screen.queryByText("Иван Петров")).toBeNull()
    expect(screen.queryByText("Исполнитель недоступен")).toBeNull()
    expect(screen.getByText("Пётр Сидоров")).toBeTruthy()
    expect(screen.getAllByText("Бригада 1").length).toBeGreaterThan(0)
    expect(screen.getByText("Норматив")).toBeTruthy()
    expect(screen.getByText("30 мин")).toBeTruthy()
    expect(screen.getByText("Активное время")).toBeTruthy()
    expect(screen.getByText("1 ч 0 мин")).toBeTruthy()
    expect(screen.getByText("Фото приёмки")).toBeTruthy()
    expect(screen.getByText("Фото после: Внутренние работы")).toBeTruthy()
    expect(
      screen.getByRole("combobox", { name: "Выбрать очередь ремонта" })
        .textContent
    ).toContain("Внутренние работы")
    expect(screen.queryByText("Показать очередь")).toBeNull()
    expect(screen.queryByText("Очередь Внутренние работы")).toBeNull()
    expect(screen.queryByText("Статус этапа")).toBeNull()
    expect(screen.queryByText(/Этап 1/)).toBeNull()
    expect(screen.queryByText("Этапы ремонта")).toBeNull()
    expect(screen.queryByText("Фото ремонта")).toBeNull()
    expect(screen.getByLabelText("Комментариев: 1").textContent).toBe("1")
    const resultGallery = screen.getByText("Фото результата").parentElement
    expect(resultGallery?.className).toContain("xl:h-full")
    const workList = screen
      .getByRole("region", { name: "Работы этапа" })
      .querySelector("ul")
    const materialList = screen
      .getByRole("region", { name: "Материалы этапа" })
      .querySelector("ul")
    expect(workList?.className).toBe("divide-y rounded-lg border text-sm")
    expect(materialList?.className).toBe(workList?.className)
    expect(screen.getByAltText("Фото 1 из 1").getAttribute("src")).toBe(
      "blob:worker-result"
    )
    expect(useServiceOwnerMedia).toHaveBeenCalledWith({
      accessToken: "token",
      owner: {
        ownerType: "TASK_BOARD_ENTRY",
        ownerId: entryId,
        warehouseId,
        context: "WORK_RESULT",
      },
    })
    expect(serviceOwnerPhotos).toHaveBeenCalledWith(
      expect.objectContaining({
        owner: {
          ownerType: "MAINTENANCE_ACCEPTANCE",
          ownerId: repairId,
          warehouseId,
          context: "ACCEPTANCE",
        },
        shrinkToContainer: true,
      })
    )
    expect(photoCarousel).toHaveBeenCalledWith(
      expect.objectContaining({
        title: "Фото результата",
        fit: "contain",
        className: "min-h-64 flex-1 rounded-lg border xl:min-h-0",
      })
    )
    expect(serviceOwnerPhotos).toHaveBeenCalledWith(
      expect.objectContaining({
        owner: {
          ownerType: "TASK_BOARD_ENTRY",
          ownerId: entryId,
          warehouseId,
          context: "WORK_RESULT",
        },
        authoritativeReadyReferences: [
          { mediaId: sourceMediaId, generation: 3 },
        ],
        presentation: "work-carousel",
      })
    )
  })

  it("places comparison photos in the result-photo card and restores result evidence after closing", async () => {
    const user = userEvent.setup()
    renderDossier(false, {
      ...task,
      origin: "INVENTORY",
      sourceInventoryId: "00000000-0000-4000-8000-000000000019",
      sourceInventoryFindingId: inventoryFindingId,
    })

    expect(
      screen.getByRole("button", { name: "Показать фото задания" })
    ).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Сравнить фотографии" })
    )

    expect(
      screen.getByText("Для сравнения: Общие фотографии задания")
    ).toBeTruthy()
    expect(
      screen.queryByText("Фото после: Внутренние работы")
    ).toBeNull()
    expect(
      screen.getByRole("button", { name: "Скрыть сравнение" })
    ).toBeTruthy()
    const generalGalleryCall = serviceOwnerPhotos.mock.calls
      .map(([props]) => props as Record<string, unknown>)
      .find(
        (props) =>
          (props.owner as { ownerType?: string } | undefined)?.ownerType ===
            "TASK_BOARD_ENTRY" &&
          props.title === "Общие фотографии задания"
      )
    expect(generalGalleryCall).toEqual(
      expect.objectContaining({
        owner: {
          ownerType: "TASK_BOARD_ENTRY",
          ownerId: entryId,
          warehouseId,
          context: "WORK_RESULT",
        },
        visibleMediaIds: [generalMediaId],
        authoritativeReadyReferences: [
          { mediaId: generalMediaId, generation: 2 },
        ],
        coverMediaId: generalMediaId,
      })
    )
    expect(
      (generalGalleryCall?.authoritativeReadyReferences as Array<{
        mediaId: string
      }>).some((reference) => reference.mediaId === sourceMediaId)
    ).toBe(false)

    await user.click(
      screen.getByRole("button", { name: "Скрыть сравнение" })
    )
    expect(
      screen.getByText("Фото после: Внутренние работы")
    ).toBeTruthy()
  })

  it("keeps a larger bounded result row below acceptance photos", async () => {
    const user = userEvent.setup()
    renderDossier()

    await user.click(
      screen.getByRole("button", { name: "Сравнить фотографии" })
    )

    const grid = screen
      .getByLabelText("Приёмка бытовки БТ-42")
      .querySelector('[data-slot="repair-work-detail-workspace-grid"]')
    expect(grid).not.toBeNull()
    expect(grid?.className).toContain(
      "xl:grid-rows-[minmax(0,0.8fr)_minmax(0,1.2fr)]"
    )

    const comparisonCard = screen
      .getByText("Для сравнения: Общие фотографии задания")
      .closest('[data-slot="card"]')
    const detailCard = screen
      .getByRole("combobox", { name: "Выбрать очередь ремонта" })
      .closest('[data-slot="card"]')
    expect(comparisonCard?.className).toContain("xl:min-h-0")
    expect(detailCard?.className).toContain("xl:min-h-0")
    expect(
      detailCard
        ?.querySelector('[data-slot="card-content"]')
        ?.className
    ).toContain("xl:overflow-y-auto")
  })

  it("renders acceptance decisions in the supplied page-header slot", () => {
    const headerActions = document.createElement("div")
    document.body.append(headerActions)

    try {
      renderDossier(true, task, headerActions, true)

      expect(
        within(headerActions).getByRole("button", { name: "Принять" })
      ).toBeTruthy()
      expect(
        within(headerActions).getByRole("button", { name: "Списать" })
      ).toBeTruthy()
      expect(
        screen.queryByText(
          "Для приёмки добавьте хотя бы одну фотографию. На доработку можно отправить без нового фото."
        )
      ).toBeNull()
    } finally {
      headerActions.remove()
    }
  })

  it("keeps each work description and review controls in one material-style desktop row", () => {
    renderDossier(true)

    const workRow = screen.getByText("Заменить окно — 1 шт.").parentElement
    expect(workRow).toBeTruthy()
    expect(workRow?.className).toContain("sm:flex-row")
    expect(
      within(workRow!).getByRole("button", { name: "Переделать" })
    ).toBeTruthy()
    expect(
      within(workRow!).getByRole("button", { name: "Принято" })
    ).toBeTruthy()
  })

  it("uses a carousel when one physical queue has result photos from several entries", () => {
    const secondStageId = "00000000-0000-4000-8000-000000000020"
    const secondEntryId = "00000000-0000-4000-8000-000000000021"
    const secondEvidenceId = "00000000-0000-4000-8000-000000000022"
    const taskWithResultPhotoGroups: RepairTaskDto = {
      ...task,
      subtasks: [
        ...task.subtasks,
        {
          ...task.subtasks[0],
          id: secondStageId,
          taskBoardEntryId: secondEntryId,
          sortOrder: 1,
          workLines: [],
          materialLines: [],
          primaryLineId: null,
          evidence: [
            {
              ...task.subtasks[0].evidence![0],
              evidenceId: secondEvidenceId,
              entryId: secondEntryId,
            },
          ],
        },
      ],
    }

    renderDossier(false, taskWithResultPhotoGroups)

    expect(
      screen.getByRole("region", { name: "Фотографии результата этапа" })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Предыдущий слайд" })
    ).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "Следующий слайд" })
    ).toBeTruthy()
  })

  it("opens a work comment only on demand", async () => {
    const user = userEvent.setup()
    renderDossier()

    expect(screen.queryByText("Проверить герметичность")).toBeNull()
    await user.click(screen.getByRole("button", { name: "Комментарий" }))
    expect(screen.getByRole("dialog")).toBeTruthy()
    expect(screen.getByText("Комментарий к работе")).toBeTruthy()
    expect(screen.getByText("Проверить герметичность")).toBeTruthy()
  })

  it("selects another actual queue without rendering all queues together", async () => {
    const user = userEvent.setup()
    const electricalStageId = "00000000-0000-4000-8000-000000000016"
    const electricalWorkId = "00000000-0000-4000-8000-000000000017"
    const multiQueueTask: RepairTaskDto = {
      ...task,
      subtasks: [
        ...task.subtasks,
        {
          ...task.subtasks[0],
          id: electricalStageId,
          taskBoardEntryId: null,
          queueId: "00000000-0000-4000-8000-000000000023",
          queueName: "Электрика",
          sortOrder: 1,
          evidence: [],
          assignments: [],
          workerGroup: null,
          groupComment: "",
          workLines: [
            {
              ...task.subtasks[0].workLines[0],
              id: electricalWorkId,
              sourceLineKey: electricalWorkId,
              description: "Заменить автомат",
              lineComment: "",
              maintenanceMediaReferences: [],
            },
          ],
          materialLines: [],
          primaryLineId: electricalWorkId,
        },
      ],
    }
    renderDossier(false, multiQueueTask)

    const queueSelect = screen.getByRole("combobox", {
      name: "Выбрать очередь ремонта",
    })
    expect(queueSelect.textContent).toContain("Внутренние работы")
    expect(screen.queryByText("Показать очередь")).toBeNull()
    expect(screen.queryByText("Заменить автомат — 1 шт.")).toBeNull()
    await user.click(queueSelect)
    await user.click(screen.getByRole("option", { name: "Электрика" }))

    expect(
      screen.getByRole("combobox", { name: "Выбрать очередь ремонта" })
        .textContent
    ).toContain("Электрика")
    expect(screen.getByText("Фото после: Электрика")).toBeTruthy()
    expect(screen.getByText("Заменить автомат — 1 шт.")).toBeTruthy()
    expect(screen.queryByText("Очередь Электрика")).toBeNull()
  })

  it("coalesces historical stages of one physical queue into one selector option", async () => {
    const user = userEvent.setup()
    const secondStageId = "00000000-0000-4000-8000-000000000020"
    const secondWorkId = "00000000-0000-4000-8000-000000000021"
    const secondMaterialId = "00000000-0000-4000-8000-000000000022"
    const duplicateQueueTask: RepairTaskDto = {
      ...task,
      subtasks: [
        ...task.subtasks,
        {
          ...task.subtasks[0],
          id: secondStageId,
          taskBoardEntryId: null,
          sortOrder: 1,
          queuePosition: 1,
          plannedDurationMinutes: 20,
          activeWorkSeconds: 600,
          evidence: [],
          assignments: [],
          workerGroup: null,
          groupComment: "Финишная группа",
          workLines: [
            {
              ...task.subtasks[0].workLines[0],
              id: secondWorkId,
              sourceLineKey: secondWorkId,
              description: "Влажная уборка",
              lineComment: "",
              maintenanceMediaReferences: [],
            },
          ],
          materialLines: [
            {
              ...task.subtasks[0].materialLines[0],
              id: secondMaterialId,
              sourceLineKey: secondMaterialId,
              description: "Буклет",
              quantity: 1,
            },
          ],
          primaryLineId: secondWorkId,
        },
      ],
    }
    renderDossier(false, duplicateQueueTask)

    expect(screen.getByText("Заменить окно — 1 шт.")).toBeTruthy()
    expect(screen.getByText("Влажная уборка — 1 шт.")).toBeTruthy()
    expect(screen.getByText("ПВХ панель белая — 2 шт.")).toBeTruthy()
    expect(screen.getByText("Буклет — 1 шт.")).toBeTruthy()
    expect(screen.getByText("50 мин")).toBeTruthy()
    expect(screen.getByText("1 ч 10 мин")).toBeTruthy()

    await user.click(
      screen.getByRole("combobox", { name: "Выбрать очередь ремонта" })
    )
    expect(screen.getAllByRole("option")).toHaveLength(1)
    expect(
      screen.getByRole("option", { name: "Внутренние работы" })
    ).toBeTruthy()
  })

  it("records a decision for each work and opens rework for the selected line", async () => {
    const user = userEvent.setup()
    renderDossier(true)

    await user.click(screen.getByRole("button", { name: "Переделать" }))
    expect(
      screen.getByRole("button", { name: "Создать доработку (1)" })
    ).toBeTruthy()
    await user.click(
      screen.getByRole("button", { name: "Создать доработку (1)" })
    )
    expect(screen.getByText("Создать доработку")).toBeTruthy()
    expect(
      screen.getByText(/автоматически попадут отмеченные для переделки работы/)
    ).toBeTruthy()
  })

  it("reads capital work photos from the estimate when no task-board entry exists", () => {
    const capitalTask: RepairTaskDto = {
      ...task,
      origin: "ESTIMATE",
      sourceEstimateId: "00000000-0000-4000-8000-000000000013",
      subtasks: task.subtasks.map((subtask) => ({
        ...subtask,
        taskBoardEntryId: null,
      })),
    }

    renderDossier(false, capitalTask)

    expect(serviceOwnerPhotos).toHaveBeenCalledWith(
      expect.objectContaining({
        owner: {
          ownerType: "MAINTENANCE_ESTIMATE",
          ownerId: capitalTask.sourceEstimateId,
          warehouseId,
          context: "ESTIMATE",
        },
        authoritativeReadyReferences: [
          { mediaId: sourceMediaId, generation: 3 },
        ],
      })
    )
  })

  it("does not substitute a different media generation", () => {
    useServiceOwnerMedia.mockReturnValue({
      assets: [
        {
          id: mediaId,
          kind: "IMAGE",
          status: "READY",
          generation: 5,
        },
      ],
      photos: [
        {
          id: mediaId,
          url: "blob:newer-generation",
          variants: { medium: { url: "blob:newer-generation" } },
        },
      ],
      query: {
        isLoading: false,
        isError: false,
        isSuccess: true,
      },
      requestFullscreen: vi.fn(),
      previewUnavailable: false,
    })

    renderDossier()

    expect(screen.queryByAltText("Фото 1 из 1")).toBeNull()
    expect(screen.getByText(/1 фото ещё недоступно/)).toBeTruthy()
  })
})
