// @vitest-environment jsdom

import { beforeEach, describe, expect, it } from "vitest"

import type { WorkQueueRequest } from "@/features/settings/task-board/model/task-board-settings"
import { BrowserTaskBoardClient } from "@/features/task-board/mock/browser-task-board-client"
import {
  MSK_WAREHOUSE_ID,
  SPB_WAREHOUSE_ID,
  TASK_BOARD_MOCK_GROUP_IDS,
  TASK_BOARD_MOCK_STORAGE_KEY,
} from "@/features/task-board/mock/seed"

const TOKEN = "mock"

describe("BrowserTaskBoardClient", () => {
  let client: BrowserTaskBoardClient

  beforeEach(() => {
    window.localStorage.clear()
    client = new BrowserTaskBoardClient()
  })

  it("creates the stable workforce, qualifications and queue registry once", async () => {
    const [classes, spbQueues, mskQueues, workers, groups, schedules] =
      await Promise.all([
        client.listClasses(TOKEN),
        client.listQueues(TOKEN, SPB_WAREHOUSE_ID),
        client.listQueues(TOKEN, MSK_WAREHOUSE_ID),
        client.listWorkers(TOKEN, SPB_WAREHOUSE_ID),
        client.listGroups(TOKEN, SPB_WAREHOUSE_ID),
        client.listSchedules(SPB_WAREHOUSE_ID),
      ])

    expect(classes.map((item) => item.code)).toEqual([
      "DRIVER",
      "GENERAL_WORKER",
      "RIGGER",
      "ELECTRICIAN",
      "PLUMBER",
      "WELDER",
      "SES",
    ])
    expect(spbQueues).toHaveLength(8)
    expect(mskQueues).toHaveLength(8)
    expect(spbQueues.at(-1)?.code).toBe("HOLDING")
    expect(workers).toHaveLength(18)
    expect(
      workers.find((item) => item.displayName === "Алексей Соколов")
    ).toBeTruthy()
    expect(
      workers.find((item) => item.displayName === "Дмитрий Орлов")
    ).toBeTruthy()
    expect(
      workers
        .find((item) => item.displayName === "Иван Петров")
        ?.qualifications.map((item) => item.workerClass.code)
    ).toEqual(["GENERAL_WORKER", "RIGGER"])
    expect(groups).toHaveLength(9)
    expect(schedules).toHaveLength(groups.length)
    expect(schedules[0].returnGraceMinutes).toBe(3)
    expect(
      schedules[0].days[0].restPeriods.map((item) => item.startsAt)
    ).toEqual(["11:00", "13:00", "16:00"])

    const initialSnapshot = await client.getSnapshot(SPB_WAREHOUSE_ID)
    const activeDemo = initialSnapshot.tasks.find(
      (task) => task.externalTaskId === "demo:internal_works:1"
    )!
    const routedDemo = initialSnapshot.tasks.find(
      (task) => task.externalTaskId === "demo:internal_works:7"
    )!
    expect(Date.parse(activeDemo.activeSince!)).toBeLessThanOrEqual(Date.now())
    expect(routedDemo.queueCode).toBe(routedDemo.route[0].queueCode)
    expect(routedDemo.route.at(-1)?.queueCode).toBe("HOLDING")

    await client.getSnapshot(SPB_WAREHOUSE_ID)
    expect(await client.listWorkers(TOKEN, SPB_WAREHOUSE_ID)).toHaveLength(18)
    expect(await client.listQueues(TOKEN, SPB_WAREHOUSE_ID)).toHaveLength(8)
  })

  it("keeps passwords out of the persisted envelope", async () => {
    const workerClass = (await client.listClasses(TOKEN))[0]
    await client.createWorker(TOKEN, SPB_WAREHOUSE_ID, {
      version: 0,
      displayName: "Тестовый рабочий",
      firstName: "Тест",
      lastName: "Рабочий",
      middleName: null,
      active: true,
      comment: null,
      appLogin: "test.worker",
      password: "must-not-persist",
      qualifications: [
        { workerClassId: workerClass.id, active: true, comment: null },
      ],
    })

    expect(
      window.localStorage.getItem(TASK_BOARD_MOCK_STORAGE_KEY)
    ).not.toContain("must-not-persist")
  })

  it("migrates an older envelope without overwriting user changes", async () => {
    const queue = (await client.listQueues(TOKEN, SPB_WAREHOUSE_ID))[0]
    await client.updateQueue(TOKEN, SPB_WAREHOUSE_ID, queue.id, {
      version: queue.version,
      code: queue.code,
      name: "Моё название перемещения",
      description: queue.description,
      type: queue.type,
      active: queue.active,
      hidden: queue.hidden,
      collapsed: queue.collapsed,
      holdingPeriodMinutes: queue.holdingPeriodMinutes,
      notificationThreshold: queue.notificationThreshold,
      notifyWhenThresholdReached: queue.notifyWhenThresholdReached,
      bindings: queue.bindings.map((binding) => ({
        workerClassId: binding.workerClass.id,
        stopTaskOnTake: binding.stopTaskOnTake,
      })),
    })
    const legacy = JSON.parse(
      window.localStorage.getItem(TASK_BOARD_MOCK_STORAGE_KEY)!
    ) as Record<string, unknown>
    legacy.schemaVersion = 1
    legacy.seedVersion = 0
    delete legacy.schedules
    window.localStorage.setItem(
      TASK_BOARD_MOCK_STORAGE_KEY,
      JSON.stringify(legacy)
    )

    const migrated = new BrowserTaskBoardClient()
    expect((await migrated.listQueues(TOKEN, SPB_WAREHOUSE_ID))[0].name).toBe(
      "Моё название перемещения"
    )
    expect(await migrated.listSchedules(SPB_WAREHOUSE_ID)).toHaveLength(9)
  })

  it("serializes concurrent mutations and prevents duplicate queue codes", async () => {
    const queue = (await client.listQueues(TOKEN, SPB_WAREHOUSE_ID))[0]
    const request: WorkQueueRequest = {
      version: 0,
      code: "CONCURRENT_TEST",
      name: "Параллельная очередь",
      description: null,
      type: "REPAIR",
      active: true,
      hidden: false,
      collapsed: false,
      holdingPeriodMinutes: null,
      notificationThreshold: null,
      notifyWhenThresholdReached: false,
      bindings: queue.bindings.map((binding) => ({
        workerClassId: binding.workerClass.id,
        stopTaskOnTake: false,
      })),
    }
    const results = await Promise.allSettled([
      client.createQueue(TOKEN, SPB_WAREHOUSE_ID, request),
      client.createQueue(TOKEN, SPB_WAREHOUSE_ID, request),
    ])

    expect(results.filter((item) => item.status === "fulfilled")).toHaveLength(
      1
    )
    expect(results.filter((item) => item.status === "rejected")).toHaveLength(1)
    expect(
      (await client.listQueues(TOKEN, SPB_WAREHOUSE_ID)).filter(
        (item) => item.code === request.code
      )
    ).toHaveLength(1)
  })

  it("returns a 409-equivalent conflict and keeps HOLDING last", async () => {
    const queues = await client.listQueues(TOKEN, SPB_WAREHOUSE_ID)
    const first = queues[0]
    const request: WorkQueueRequest = {
      version: first.version,
      code: first.code,
      name: "Перемещения и переносы",
      description: first.description,
      type: first.type,
      active: first.active,
      hidden: first.hidden,
      collapsed: first.collapsed,
      holdingPeriodMinutes: first.holdingPeriodMinutes,
      notificationThreshold: first.notificationThreshold,
      notifyWhenThresholdReached: first.notifyWhenThresholdReached,
      bindings: first.bindings.map((binding) => ({
        workerClassId: binding.workerClass.id,
        stopTaskOnTake: binding.stopTaskOnTake,
      })),
    }
    await client.updateQueue(TOKEN, SPB_WAREHOUSE_ID, first.id, request)
    await expect(
      client.updateQueue(TOKEN, SPB_WAREHOUSE_ID, first.id, request)
    ).rejects.toMatchObject({ status: 409 })

    const current = await client.listQueues(TOKEN, SPB_WAREHOUSE_ID)
    const reordered = await client.reorderQueues(
      TOKEN,
      SPB_WAREHOUSE_ID,
      [...current]
        .reverse()
        .map((queue) => ({ queueId: queue.id, expectedVersion: queue.version }))
    )
    expect(reordered.at(-1)?.type).toBe("HOLDING")
  })

  it("does not change the code of a queue referenced by a task route", async () => {
    const queue = (await client.listQueues(TOKEN, SPB_WAREHOUSE_ID)).find(
      (item) => item.code === "ELECTRICS"
    )!
    await expect(
      client.updateQueue(TOKEN, SPB_WAREHOUSE_ID, queue.id, {
        version: queue.version,
        code: "ELECTRICS_RENAMED",
        name: queue.name,
        description: queue.description,
        type: queue.type,
        active: queue.active,
        hidden: queue.hidden,
        collapsed: queue.collapsed,
        holdingPeriodMinutes: queue.holdingPeriodMinutes,
        notificationThreshold: queue.notificationThreshold,
        notifyWhenThresholdReached: queue.notifyWhenThresholdReached,
        bindings: queue.bindings.map((binding) => ({
          workerClassId: binding.workerClass.id,
          stopTaskOnTake: binding.stopTaskOnTake,
        })),
      })
    ).rejects.toMatchObject({ status: 409 })
  })

  it("copies a valid schedule and rejects overlapping periods", async () => {
    const schedules = await client.listSchedules(SPB_WAREHOUSE_ID)
    const source = schedules[1]
    const target = schedules[2]
    const [copied] = await client.copySchedule(SPB_WAREHOUSE_ID, {
      sourceGroupId: source.workerGroupId,
      sourceExpectedVersion: source.version,
      targets: [
        { groupId: target.workerGroupId, expectedVersion: target.version },
      ],
    })
    expect(
      copied.days.map((day) => ({
        ...day,
        restPeriods: day.restPeriods.map((period) => ({
          ...period,
          id: "ignored",
        })),
      }))
    ).toEqual(
      source.days.map((day) => ({
        ...day,
        restPeriods: day.restPeriods.map((period) => ({
          ...period,
          id: "ignored",
        })),
      }))
    )
    expect(copied.version).toBe(target.version + 1)

    const invalidDays = structuredClone(copied.days)
    invalidDays[0].restPeriods[1].startsAt = "11:05"
    await expect(
      client.saveSchedule(SPB_WAREHOUSE_ID, copied.workerGroupId, {
        version: copied.version,
        timezone: copied.timezone,
        returnGraceMinutes: copied.returnGraceMinutes,
        days: invalidDays,
      })
    ).rejects.toThrow("не должны пересекаться")

    await expect(
      client.saveSchedule(SPB_WAREHOUSE_ID, copied.workerGroupId, {
        version: copied.version,
        timezone: copied.timezone,
        returnGraceMinutes: -1,
        days: copied.days,
      })
    ).rejects.toThrow("от 0 до 60")

    const invalidTimeDays = structuredClone(copied.days)
    invalidTimeDays[0].shiftStartsAt = "NaN:00"
    await expect(
      client.saveSchedule(SPB_WAREHOUSE_ID, copied.workerGroupId, {
        version: copied.version,
        timezone: copied.timezone,
        returnGraceMinutes: copied.returnGraceMinutes,
        days: invalidTimeDays,
      })
    ).rejects.toThrow("корректное время смены")

    const invalidWarningDays = structuredClone(copied.days)
    invalidWarningDays[0].restPeriods[0].warningMinutes = -1
    await expect(
      client.saveSchedule(SPB_WAREHOUSE_ID, copied.workerGroupId, {
        version: copied.version,
        timezone: copied.timezone,
        returnGraceMinutes: copied.returnGraceMinutes,
        days: invalidWarningDays,
      })
    ).rejects.toThrow("Предупреждение")
  })

  it("validates IANA timezones and repairs a migrated invalid zone on evaluation", async () => {
    const schedules = await client.listSchedules(SPB_WAREHOUSE_ID)
    const source = schedules.find(
      (schedule) =>
        schedule.workerGroupId === TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1
    )!
    await expect(
      client.saveSchedule(SPB_WAREHOUSE_ID, source.workerGroupId, {
        version: source.version,
        timezone: "Mars/Olympus",
        returnGraceMinutes: source.returnGraceMinutes,
        days: source.days,
      })
    ).rejects.toThrow("временную зону IANA")

    const persisted = JSON.parse(
      window.localStorage.getItem(TASK_BOARD_MOCK_STORAGE_KEY)!
    ) as {
      schedules: {
        workerGroupId: string
        timezone: string
        version: number
        returnGraceMinutes: number
        days: { restPeriods: { startsAt: string }[] }[]
      }[]
    }
    const corrupted = persisted.schedules.find(
      (schedule) => schedule.workerGroupId === source.workerGroupId
    )!
    corrupted.timezone = "Mars/Olympus"
    corrupted.returnGraceMinutes = -4
    corrupted.days[0].restPeriods[0].startsAt = "not-a-time"
    window.localStorage.setItem(
      TASK_BOARD_MOCK_STORAGE_KEY,
      JSON.stringify(persisted)
    )
    const migrated = new BrowserTaskBoardClient()
    await expect(
      migrated.copySchedule(SPB_WAREHOUSE_ID, {
        sourceGroupId: source.workerGroupId,
        sourceExpectedVersion: corrupted.version,
        targets: [
          {
            groupId: schedules[2].workerGroupId,
            expectedVersion: schedules[2].version,
          },
        ],
      })
    ).rejects.toThrow("временную зону IANA")

    await expect(migrated.getSnapshot(SPB_WAREHOUSE_ID)).resolves.toBeTruthy()
    expect(
      (await migrated.listSchedules(SPB_WAREHOUSE_ID)).find(
        (schedule) => schedule.workerGroupId === source.workerGroupId
      )
    ).toMatchObject({ timezone: "Europe/Moscow", returnGraceMinutes: 3 })
  })

  it("preserves break templates while a weekday is disabled", async () => {
    const schedule = (await client.listSchedules(SPB_WAREHOUSE_ID))[2]
    const disabledDays = structuredClone(schedule.days)
    disabledDays[0].enabled = false
    const saved = await client.saveSchedule(
      SPB_WAREHOUSE_ID,
      schedule.workerGroupId,
      {
        version: schedule.version,
        timezone: schedule.timezone,
        returnGraceMinutes: schedule.returnGraceMinutes,
        days: disabledDays,
      }
    )
    expect(saved.days[0]).toMatchObject({
      enabled: false,
      restPeriods: expect.arrayContaining([
        expect.objectContaining({ type: "SMOKE_BREAK" }),
      ]),
    })

    const invalidEnabledDays = structuredClone(saved.days)
    invalidEnabledDays[0].enabled = true
    invalidEnabledDays[0].restPeriods[0].startsAt = "broken"
    await expect(
      client.saveSchedule(SPB_WAREHOUSE_ID, saved.workerGroupId, {
        version: saved.version,
        timezone: saved.timezone,
        returnGraceMinutes: saved.returnGraceMinutes,
        days: invalidEnabledDays,
      })
    ).rejects.toThrow("корректное время перерыва")
  })

  it("applies a newly saved active break immediately", async () => {
    const clock = await client.getSimulationClock()
    await client.updateSimulationClock({
      expectedVersion: clock.version,
      now: "2026-07-13T10:30:00.000+03:00",
      mode: "SIMULATION",
      running: false,
    })
    const schedule = (await client.listSchedules(SPB_WAREHOUSE_ID)).find(
      (item) => item.workerGroupId === TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1
    )!
    const days = structuredClone(schedule.days)
    days[0].restPeriods[0].startsAt = "10:25"
    days[0].restPeriods[0].endsAt = "10:35"
    const saved = await client.saveSchedule(
      SPB_WAREHOUSE_ID,
      schedule.workerGroupId,
      {
        version: schedule.version,
        timezone: schedule.timezone,
        returnGraceMinutes: schedule.returnGraceMinutes,
        days,
      }
    )

    expect(
      (await client.getSnapshot(SPB_WAREHOUSE_ID)).tasks.find(
        (task) => task.externalTaskId === "demo:internal_works:1"
      )
    ).toMatchObject({
      status: "PAUSED",
      pauseReasons: [expect.objectContaining({ type: "SCHEDULE" })],
    })

    const disabledDays = structuredClone(saved.days)
    disabledDays[0].restPeriods[0].autoPause = false
    await client.saveSchedule(SPB_WAREHOUSE_ID, saved.workerGroupId, {
      version: saved.version,
      timezone: saved.timezone,
      returnGraceMinutes: saved.returnGraceMinutes,
      days: disabledDays,
    })
    expect(
      (await client.getSnapshot(SPB_WAREHOUSE_ID)).tasks.find(
        (task) => task.externalTaskId === "demo:internal_works:1"
      )
    ).toMatchObject({ status: "IN_PROGRESS", pauseReasons: [] })
  })

  it("interrupts the group's work for MOVEMENT and resumes after grace", async () => {
    const movement = await client.registerTask({
      externalTaskId: "test:movement:1",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "MOVEMENT",
      title: "Перенести шкаф",
      cabinNumber: "БЫТ-002",
    })
    const taken = await client.takeTask({
      taskId: movement.id,
      expectedVersion: movement.version,
      workerGroupId: TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1,
    })
    let snapshot = await client.getSnapshot(SPB_WAREHOUSE_ID)
    const interrupted = snapshot.tasks.find(
      (task) => task.externalTaskId === "demo:internal_works:1"
    )
    expect(interrupted?.status).toBe("PAUSED")
    expect(interrupted?.pauseReasons.map((reason) => reason.type)).toContain(
      "INTERRUPTION"
    )
    expect(snapshot.interruptions[0]?.state).toBe("ACTIVE")

    await client.completeTask({
      taskId: taken.id,
      expectedVersion: taken.version,
    })
    snapshot = await client.getSnapshot(SPB_WAREHOUSE_ID)
    expect(
      snapshot.tasks.find(
        (task) => task.externalTaskId === "demo:internal_works:1"
      )?.status
    ).toBe("RETURNING")

    const clock = await client.getSimulationClock()
    const resumeAt = new Date(Date.parse(clock.now) + 4 * 60_000).toISOString()
    await client.updateSimulationClock({
      expectedVersion: clock.version,
      now: resumeAt,
      mode: "SIMULATION",
      running: false,
    })
    snapshot = await client.getSnapshot(SPB_WAREHOUSE_ID)
    expect(
      snapshot.tasks.find(
        (task) => task.externalTaskId === "demo:internal_works:1"
      )?.status
    ).toBe("IN_PROGRESS")
    expect(snapshot.interruptions[0]?.state).toBe("RESUMED")
    const generalGroup = snapshot.groups.find(
      (group) => group.id === TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1
    )!
    for (const member of generalGroup.members) {
      expect(
        (await client.listNotifications(member.workerId)).some(
          (item) => item.type === "TASK_RESUMED"
        )
      ).toBe(true)
    }
  })

  it("registers an external task idempotently", async () => {
    const command = {
      externalTaskId: "logistics:stable-task-1",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "MOVEMENT",
      title: "Подготовить бытовку",
      route: [
        {
          externalStepId: "repair-subtask-42",
          queueCode: "MOVEMENT",
          title: "Перемещение",
        },
      ],
    }
    const first = await client.registerTask(command)
    const repeated = await client.registerTask(command)
    const snapshot = await client.getSnapshot(SPB_WAREHOUSE_ID)

    expect(repeated.id).toBe(first.id)
    expect(repeated.route[0]?.externalStepId).toBe("repair-subtask-42")
    expect(
      snapshot.tasks.filter(
        (task) => task.externalTaskId === command.externalTaskId
      )
    ).toHaveLength(1)
    expect(
      await client.findByExternalTaskId(
        SPB_WAREHOUSE_ID,
        command.externalTaskId
      )
    ).toMatchObject({ id: first.id })
  })

  it("does not persist or emit changes for a reconcile without transitions", async () => {
    const before = await client.getSnapshot(SPB_WAREHOUSE_ID)
    let events = 0
    const unsubscribe = client.subscribe(() => {
      events += 1
    })

    await client.evaluate("2026-07-12T10:00:00.000+03:00")
    await client.evaluate("2026-07-12T10:00:01.000+03:00")
    const after = await client.getSnapshot(SPB_WAREHOUSE_ID)
    unsubscribe()

    expect(after.revision).toBe(before.revision)
    expect(events).toBe(0)
  })

  it("amends and fully reorders an unstarted repair route without version churn", async () => {
    const registered = await client.registerTask({
      externalTaskId: "repair:sync-unstarted",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "INTERNAL_WORKS",
      title: "Исходный ремонт",
      cabinNumber: "БЫТ-010",
      route: [
        {
          externalStepId: "step-a",
          queueCode: "INTERNAL_WORKS",
          title: "Внутренние работы",
        },
        {
          externalStepId: "step-b",
          queueCode: "ELECTRICS",
          title: "Электрика",
        },
      ],
    })
    const queuePosition = registered.queuePosition
    const command = {
      warehouseId: SPB_WAREHOUSE_ID,
      externalTaskId: registered.externalTaskId,
      expectedVersion: registered.version,
      title: "Уточнённый ремонт",
      description: "Маршрут изменён до начала работ",
      cabinNumber: "БЫТ-010",
      sourceStatus: "ACTIVE" as const,
      route: [
        {
          externalStepId: "step-b",
          queueCode: "ELECTRICS",
          title: "Электрика",
        },
        {
          externalStepId: "step-a",
          queueCode: "INTERNAL_WORKS",
          title: "Внутренние работы",
        },
      ],
    }
    const synced = await client.syncRegisteredTask(command)
    expect(synced).toMatchObject({
      title: command.title,
      description: command.description,
      queueCode: "ELECTRICS",
      queuePosition,
      assignmentId: null,
      activeRouteIndex: 0,
    })
    expect(synced.route.map((step) => step.externalStepId)).toEqual([
      "step-b",
      "step-a",
    ])

    let events = 0
    const unsubscribe = client.subscribe(() => {
      events += 1
    })
    const repeated = await client.syncRegisteredTask({
      ...command,
      expectedVersion: synced.version,
    })
    unsubscribe()
    expect(repeated.version).toBe(synced.version)
    expect(events).toBe(0)
  })

  it("preserves active execution while replacing only future repair steps", async () => {
    const registered = await client.registerTask({
      externalTaskId: "repair:sync-active",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "INTERNAL_WORKS",
      title: "Активный ремонт",
      route: [
        {
          externalStepId: "active-step",
          queueCode: "INTERNAL_WORKS",
          title: "Текущий этап",
        },
        {
          externalStepId: "removed-future",
          queueCode: "ELECTRICS",
          title: "Старый будущий этап",
        },
        {
          externalStepId: "kept-future",
          queueCode: "HOLDING",
          title: "Ожидание",
        },
      ],
    })
    const taken = await client.takeTask({
      taskId: registered.id,
      expectedVersion: registered.version,
      workerGroupId: TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1,
    })
    const assignmentId = taken.assignmentId
    const queuePosition = taken.queuePosition
    const synced = await client.syncRegisteredTask({
      warehouseId: SPB_WAREHOUSE_ID,
      externalTaskId: taken.externalTaskId,
      expectedVersion: taken.version,
      title: "Активный ремонт — уточнение",
      cabinNumber: "БЫТ-011",
      sourceStatus: "ACTIVE",
      route: [
        {
          externalStepId: "active-step",
          queueCode: "INTERNAL_WORKS",
          title: "Текущий этап уточнён",
        },
        {
          externalStepId: "kept-future",
          queueCode: "HOLDING",
          title: "Ожидание",
        },
        {
          externalStepId: "new-future",
          queueCode: "PLUMBING",
          title: "Новая сантехника",
        },
      ],
    })
    expect(synced).toMatchObject({
      status: "IN_PROGRESS",
      assignmentId,
      queueCode: "INTERNAL_WORKS",
      queuePosition,
      activeRouteIndex: 0,
    })
    expect(synced.route.map((step) => step.externalStepId)).toEqual([
      "active-step",
      "kept-future",
      "new-future",
    ])
    await expect(
      client.syncRegisteredTask({
        warehouseId: SPB_WAREHOUSE_ID,
        externalTaskId: synced.externalTaskId,
        expectedVersion: synced.version,
        title: synced.title,
        sourceStatus: "ACTIVE",
        route: [
          {
            externalStepId: "kept-future",
            queueCode: "HOLDING",
            title: "Ожидание",
          },
        ],
      })
    ).rejects.toMatchObject({ status: 409 })

    const cancelled = await client.syncRegisteredTask({
      warehouseId: SPB_WAREHOUSE_ID,
      externalTaskId: synced.externalTaskId,
      expectedVersion: synced.version,
      title: synced.title,
      cabinNumber: synced.cabinNumber,
      sourceStatus: "CANCELLED",
      route: synced.route.map((step) => ({
        externalStepId: step.externalStepId!,
        queueCode: step.queueCode,
        title: step.title,
      })),
    })
    expect(cancelled).toMatchObject({ status: "CANCELLED", assignmentId })
    const snapshot = await client.getSnapshot(SPB_WAREHOUSE_ID)
    expect(
      snapshot.assignments.find((assignment) => assignment.id === assignmentId)
        ?.status
    ).toBe("CANCELLED")
  })

  it("accepts a source-completed repair task but rejects mutable sync for logistics", async () => {
    const repair = await client.registerTask({
      externalTaskId: "repair:sync-completed",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "INTERNAL_WORKS",
      title: "Завершённый источник",
      route: [
        {
          externalStepId: "only-step",
          queueCode: "INTERNAL_WORKS",
          title: "Работы",
        },
      ],
    })
    expect(
      await client.syncRegisteredTask({
        warehouseId: SPB_WAREHOUSE_ID,
        externalTaskId: repair.externalTaskId,
        expectedVersion: repair.version,
        title: repair.title,
        sourceStatus: "COMPLETED",
        route: [
          {
            externalStepId: "only-step",
            queueCode: "INTERNAL_WORKS",
            title: "Работы",
          },
        ],
      })
    ).toMatchObject({ status: "DONE" })

    const logistics = await client.registerTask({
      externalTaskId: "logistics:immutable",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "MOVEMENT",
      title: "Логистическое задание",
    })
    await expect(
      client.syncRegisteredTask({
        warehouseId: SPB_WAREHOUSE_ID,
        externalTaskId: logistics.externalTaskId,
        expectedVersion: logistics.version,
        title: logistics.title,
        sourceStatus: "CANCELLED",
        route: [],
      })
    ).rejects.toThrow("только ремонтным")
  })

  it("tolerates inactive current routes but requires active changed future queues", async () => {
    const registered = await client.registerTask({
      externalTaskId: "repair:inactive-route",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "INTERNAL_WORKS",
      title: "Ремонт с отключённой очередью",
      route: [
        {
          externalStepId: "inactive-current",
          queueCode: "INTERNAL_WORKS",
          title: "Текущий этап",
        },
        {
          externalStepId: "inactive-future",
          queueCode: "ELECTRICS",
          title: "Будущий этап",
        },
      ],
    })
    const completedCandidate = await client.registerTask({
      externalTaskId: "repair:inactive-route-completed",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "INTERNAL_WORKS",
      title: "Завершаемый ремонт с отключённой очередью",
      route: [
        {
          externalStepId: "completed-current",
          queueCode: "INTERNAL_WORKS",
          title: "Текущий этап",
        },
        {
          externalStepId: "completed-future",
          queueCode: "ELECTRICS",
          title: "Будущий этап",
        },
      ],
    })
    const queues = await client.listQueues(TOKEN, SPB_WAREHOUSE_ID)
    for (const code of ["INTERNAL_WORKS", "ELECTRICS"]) {
      const queue = queues.find((candidate) => candidate.code === code)!
      await client.updateQueue(TOKEN, SPB_WAREHOUSE_ID, queue.id, {
        version: queue.version,
        code: queue.code,
        name: queue.name,
        description: queue.description,
        type: queue.type,
        active: false,
        hidden: queue.hidden,
        collapsed: queue.collapsed,
        holdingPeriodMinutes: queue.holdingPeriodMinutes,
        notificationThreshold: queue.notificationThreshold,
        notifyWhenThresholdReached: queue.notifyWhenThresholdReached,
        bindings: queue.bindings.map((binding) => ({
          workerClassId: binding.workerClass.id,
          stopTaskOnTake: binding.stopTaskOnTake,
        })),
      })
    }

    const unchangedRoute = registered.route.map((step) => ({
      externalStepId: step.externalStepId!,
      queueCode: step.queueCode,
      title: step.title,
    }))
    const amended = await client.syncRegisteredTask({
      warehouseId: SPB_WAREHOUSE_ID,
      externalTaskId: registered.externalTaskId,
      expectedVersion: registered.version,
      title: "Метаданные уточнены",
      sourceStatus: "ACTIVE",
      route: unchangedRoute,
    })
    await expect(
      client.syncRegisteredTask({
        warehouseId: SPB_WAREHOUSE_ID,
        externalTaskId: amended.externalTaskId,
        expectedVersion: amended.version,
        title: amended.title,
        sourceStatus: "ACTIVE",
        route: [
          unchangedRoute[0],
          { ...unchangedRoute[1], title: "Изменённый будущий этап" },
        ],
      })
    ).rejects.toThrow("не найдена или неактивна")

    expect(
      await client.syncRegisteredTask({
        warehouseId: SPB_WAREHOUSE_ID,
        externalTaskId: amended.externalTaskId,
        expectedVersion: amended.version,
        title: amended.title,
        sourceStatus: "CANCELLED",
        route: [
          unchangedRoute[0],
          {
            ...unchangedRoute[1],
            queueCode: "QUEUE_ALREADY_DELETED",
          },
        ],
      })
    ).toMatchObject({ status: "CANCELLED" })
    expect(
      await client.syncRegisteredTask({
        warehouseId: SPB_WAREHOUSE_ID,
        externalTaskId: completedCandidate.externalTaskId,
        expectedVersion: completedCandidate.version,
        title: completedCandidate.title,
        sourceStatus: "COMPLETED",
        route: [
          {
            externalStepId: "completed-current",
            queueCode: "INTERNAL_WORKS",
            title: "Текущий этап",
          },
          {
            externalStepId: "completed-future",
            queueCode: "QUEUE_ALREADY_DELETED",
            title: "Будущий этап",
          },
        ],
      })
    ).toMatchObject({ status: "DONE" })
  })

  it("keeps an early-returned group paused until the overlapping break ends", async () => {
    const initialClock = await client.getSimulationClock()
    const clock = await client.updateSimulationClock({
      expectedVersion: initialClock.version,
      now: "2026-07-13T10:58:00.000+03:00",
      mode: "SIMULATION",
      running: false,
    })
    const movement = await client.registerTask({
      externalTaskId: "test:movement:early-return",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "MOVEMENT",
      title: "Срочно перенести кровать",
    })
    const taken = await client.takeTask({
      taskId: movement.id,
      expectedVersion: movement.version,
      workerGroupId: TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1,
    })
    await client.completeTask({
      taskId: taken.id,
      expectedVersion: taken.version,
    })
    let snapshot = await client.getSnapshot(SPB_WAREHOUSE_ID)
    const returningTask = snapshot.tasks.find(
      (task) => task.externalTaskId === "demo:internal_works:1"
    )!
    await expect(
      client.moveTask({
        taskId: returningTask.id,
        expectedVersion: returningTask.version,
        targetQueueId: null,
        queuePosition: 10,
      })
    ).rejects.toMatchObject({ status: 409 })
    const returning = snapshot.interruptions.find(
      (interruption) => interruption.state === "RETURNING"
    )!
    const persisted = JSON.parse(
      window.localStorage.getItem(TASK_BOARD_MOCK_STORAGE_KEY)!
    ) as {
      clock: {
        anchorSimulatedAt: string
        anchorRealAt: string
        running: boolean
      }
    }
    persisted.clock.anchorSimulatedAt = "2026-07-13T11:00:00.000+03:00"
    persisted.clock.anchorRealAt = new Date().toISOString()
    persisted.clock.running = false
    window.localStorage.setItem(
      TASK_BOARD_MOCK_STORAGE_KEY,
      JSON.stringify(persisted)
    )

    const resumed = await client.confirmGroupReturned({
      workerGroupId: TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1,
      interruptions: [{ id: returning.id, expectedVersion: returning.version }],
    })
    expect(resumed).toHaveLength(1)
    expect(resumed[0]).toMatchObject({
      externalTaskId: "demo:internal_works:1",
      status: "PAUSED",
      activeSince: null,
      pauseReasons: [expect.objectContaining({ type: "SCHEDULE" })],
    })
    await expect(
      client.confirmGroupReturned({
        workerGroupId: TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1,
        interruptions: [
          { id: returning.id, expectedVersion: returning.version },
        ],
      })
    ).rejects.toMatchObject({ status: 409 })

    await client.updateSimulationClock({
      expectedVersion: clock.version,
      now: "2026-07-13T11:11:00.000+03:00",
      running: false,
    })
    snapshot = await client.getSnapshot(SPB_WAREHOUSE_ID)
    expect(
      snapshot.tasks.find(
        (task) => task.externalTaskId === "demo:internal_works:1"
      )?.status
    ).toBe("IN_PROGRESS")
  })

  it("keeps interrupted work on its break when movement cancellation happens before a tick", async () => {
    async function verifyCancellationDuringBreak(repairSync: boolean) {
      const initialClock = await client.getSimulationClock()
      await client.updateSimulationClock({
        expectedVersion: initialClock.version,
        now: "2026-07-13T10:58:00.000+03:00",
        mode: "SIMULATION",
        running: false,
      })
      const externalTaskId = repairSync
        ? "repair:cancel-during-break"
        : "test:cancel-during-break"
      const movement = await client.registerTask({
        externalTaskId,
        warehouseId: SPB_WAREHOUSE_ID,
        queueCode: "MOVEMENT",
        title: "Отменяемое перемещение",
        route: repairSync
          ? [
              {
                externalStepId: "movement-step",
                queueCode: "MOVEMENT",
                title: "Перемещение",
              },
            ]
          : undefined,
      })
      const taken = await client.takeTask({
        taskId: movement.id,
        expectedVersion: movement.version,
        workerGroupId: TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1,
      })
      const persisted = JSON.parse(
        window.localStorage.getItem(TASK_BOARD_MOCK_STORAGE_KEY)!
      ) as {
        clock: {
          anchorSimulatedAt: string
          anchorRealAt: string
          running: boolean
        }
      }
      persisted.clock.anchorSimulatedAt = "2026-07-13T11:00:00.000+03:00"
      persisted.clock.anchorRealAt = new Date().toISOString()
      persisted.clock.running = false
      window.localStorage.setItem(
        TASK_BOARD_MOCK_STORAGE_KEY,
        JSON.stringify(persisted)
      )

      if (repairSync) {
        await client.syncRegisteredTask({
          warehouseId: SPB_WAREHOUSE_ID,
          externalTaskId: taken.externalTaskId,
          expectedVersion: taken.version,
          title: taken.title,
          sourceStatus: "CANCELLED",
          route: [
            {
              externalStepId: "movement-step",
              queueCode: "MOVEMENT",
              title: "Перемещение",
            },
          ],
        })
      } else {
        await client.cancelTask({
          taskId: taken.id,
          expectedVersion: taken.version,
        })
      }
      const interrupted = (
        await client.getSnapshot(SPB_WAREHOUSE_ID)
      ).tasks.find((task) => task.externalTaskId === "demo:internal_works:1")!
      expect(interrupted).toMatchObject({
        status: "PAUSED",
        activeSince: null,
        pauseReasons: [expect.objectContaining({ type: "SCHEDULE" })],
      })
    }

    await verifyCancellationDuringBreak(false)
    client.resetForTests()
    client = new BrowserTaskBoardClient()
    await verifyCancellationDuringBreak(true)
  })

  it("warns, pauses, catches up after reload and resumes at break end", async () => {
    let clock = await client.getSimulationClock()
    clock = await client.updateSimulationClock({
      expectedVersion: clock.version,
      now: "2026-07-13T10:55:00.000+03:00",
      mode: "SIMULATION",
      running: false,
    })
    await client.evaluate("2026-07-13T10:55:30.000+03:00")
    const warnings = (await client.listNotifications()).filter(
      (item) => item.type === "BREAK_WARNING"
    )
    expect(warnings).toHaveLength(1)

    const read = await client.markNotificationRead(
      warnings[0].id,
      warnings[0].version
    )
    expect(read.readAt).not.toBeNull()

    clock = await client.updateSimulationClock({
      expectedVersion: clock.version,
      now: "2026-07-13T11:02:00.000+03:00",
      running: false,
    })
    let snapshot = await client.getSnapshot(SPB_WAREHOUSE_ID)
    expect(
      snapshot.tasks.find(
        (task) => task.externalTaskId === "demo:internal_works:1"
      )
    ).toMatchObject({
      status: "PAUSED",
      pauseReasons: [expect.objectContaining({ type: "SCHEDULE" })],
    })

    const reloadedClient = new BrowserTaskBoardClient()
    snapshot = await reloadedClient.getSnapshot(SPB_WAREHOUSE_ID)
    expect(
      snapshot.tasks.find(
        (task) => task.externalTaskId === "demo:internal_works:1"
      )?.status
    ).toBe("PAUSED")

    await reloadedClient.updateSimulationClock({
      expectedVersion: clock.version,
      now: "2026-07-13T11:11:00.000+03:00",
      running: false,
    })
    snapshot = await reloadedClient.getSnapshot(SPB_WAREHOUSE_ID)
    expect(
      snapshot.tasks.find(
        (task) => task.externalTaskId === "demo:internal_works:1"
      )?.status
    ).toBe("IN_PROGRESS")
    expect(
      (await reloadedClient.listNotifications()).filter(
        (item) => item.type === "BREAK_WARNING"
      )
    ).toHaveLength(1)
    expect(
      (await reloadedClient.listNotifications()).some(
        (item) => item.type === "BREAK_ENDED"
      )
    ).toBe(true)

    clock = await reloadedClient.getSimulationClock()
    await reloadedClient.updateSimulationClock({
      expectedVersion: clock.version,
      now: "2026-07-14T10:55:00.000+03:00",
      running: false,
    })
    expect(
      (await reloadedClient.listNotifications()).filter(
        (item) => item.type === "BREAK_WARNING"
      )
    ).toHaveLength(2)
  })

  it("interrupts assignments by overlapping workers, not only by group id", async () => {
    const groups = await client.listGroups(TOKEN, SPB_WAREHOUSE_ID)
    const groupOne = groups.find(
      (group) => group.id === TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1
    )!
    const groupTwo = groups.find(
      (group) => group.id === TASK_BOARD_MOCK_GROUP_IDS.GENERAL_2
    )!
    const sharedWorker = groupOne.members[0]
    await client.updateGroup(TOKEN, SPB_WAREHOUSE_ID, groupTwo.id, {
      version: groupTwo.version,
      workerClassId: groupTwo.workerClass.id,
      name: groupTwo.name,
      description: groupTwo.description,
      active: true,
      members: [
        ...groupTwo.members.map((member) => ({
          workerId: member.workerId,
          roleInGroup: member.roleInGroup,
          active: member.active,
        })),
        { workerId: sharedWorker.workerId, roleInGroup: null, active: true },
      ],
    })

    const repair = await client.registerTask({
      externalTaskId: "test:overlap:repair",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "INTERNAL_WORKS",
      title: "Работа общей смены",
    })
    await client.takeTask({
      taskId: repair.id,
      expectedVersion: repair.version,
      workerGroupId: groupTwo.id,
      workerIds: [sharedWorker.workerId],
    })
    const movement = await client.registerTask({
      externalTaskId: "test:overlap:movement",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "MOVEMENT",
      title: "Срочное перемещение",
    })
    await client.takeTask({
      taskId: movement.id,
      expectedVersion: movement.version,
      workerGroupId: groupOne.id,
      workerIds: [sharedWorker.workerId],
    })

    const snapshot = await client.getSnapshot(SPB_WAREHOUSE_ID)
    expect(snapshot.tasks.find((task) => task.id === repair.id)?.status).toBe(
      "PAUSED"
    )
    expect(
      snapshot.interruptions.some(
        (interruption) => interruption.interruptedTaskId === repair.id
      )
    ).toBe(true)
  })

  it("moves only QUEUED tasks and rejects active or terminal states", async () => {
    const targetQueue = (await client.listQueues(TOKEN, SPB_WAREHOUSE_ID)).find(
      (queue) => queue.code === "EXTERNAL_WORKS"
    )!
    const queued = await client.registerTask({
      externalTaskId: "test:move-statuses",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "INTERNAL_WORKS",
      title: "Перемещаемая карточка",
    })
    const moved = await client.moveTask({
      taskId: queued.id,
      expectedVersion: queued.version,
      targetQueueId: targetQueue.id,
      queuePosition: 15,
    })
    expect(moved).toMatchObject({
      queueCode: "EXTERNAL_WORKS",
      queuePosition: 15,
    })

    const active = await client.takeTask({
      taskId: moved.id,
      expectedVersion: moved.version,
      workerGroupId: TASK_BOARD_MOCK_GROUP_IDS.GENERAL_2,
    })
    await expect(
      client.moveTask({
        taskId: active.id,
        expectedVersion: active.version,
        targetQueueId: null,
        queuePosition: 10,
      })
    ).rejects.toMatchObject({ status: 409 })
    const paused = await client.pauseTask({
      taskId: active.id,
      expectedVersion: active.version,
    })
    await expect(
      client.moveTask({
        taskId: paused.id,
        expectedVersion: paused.version,
        targetQueueId: null,
        queuePosition: 10,
      })
    ).rejects.toMatchObject({ status: 409 })
    const cancelled = await client.cancelTask({
      taskId: paused.id,
      expectedVersion: paused.version,
    })
    await expect(
      client.moveTask({
        taskId: cancelled.id,
        expectedVersion: cancelled.version,
        targetQueueId: null,
        queuePosition: 10,
      })
    ).rejects.toMatchObject({ status: 409 })

    const queuedDone = await client.registerTask({
      externalTaskId: "test:move-done",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "INTERNAL_WORKS",
      title: "Завершаемая карточка",
    })
    const done = await client.completeTask({
      taskId: queuedDone.id,
      expectedVersion: queuedDone.version,
    })
    await expect(
      client.moveTask({
        taskId: done.id,
        expectedVersion: done.version,
        targetQueueId: null,
        queuePosition: 10,
      })
    ).rejects.toMatchObject({ status: 409 })
  })

  it("rejects groups and assignees without their primary qualification", async () => {
    const classes = await client.listClasses(TOKEN)
    const workers = await client.listWorkers(TOKEN, SPB_WAREHOUSE_ID)
    const electrician = classes.find((item) => item.code === "ELECTRICIAN")!
    const generalWorker = workers.find(
      (item) => item.displayName === "Иван Петров"
    )!
    await expect(
      client.createGroup(TOKEN, SPB_WAREHOUSE_ID, {
        version: 0,
        workerClassId: electrician.id,
        name: "Некорректная бригада",
        description: null,
        active: true,
        members: [
          { workerId: generalWorker.id, roleInGroup: null, active: true },
        ],
      })
    ).rejects.toThrow("нет активной квалификации")

    const task = await client.registerTask({
      externalTaskId: "test:invalid-assignee",
      warehouseId: SPB_WAREHOUSE_ID,
      queueCode: "ELECTRICS",
      title: "Электромонтаж",
    })
    await expect(
      client.takeTask({
        taskId: task.id,
        expectedVersion: task.version,
        workerGroupId: TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1,
        workerIds: [generalWorker.id],
      })
    ).rejects.toThrow("не может взять")
  })

  it("marks only the versioned notification snapshot as read", async () => {
    let clock = await client.getSimulationClock()
    clock = await client.updateSimulationClock({
      expectedVersion: clock.version,
      now: "2026-07-13T10:55:00.000+03:00",
      mode: "SIMULATION",
      running: false,
    })
    const warning = (await client.listNotifications()).find(
      (item) => item.type === "BREAK_WARNING"
    )!
    await client.updateSimulationClock({
      expectedVersion: clock.version,
      now: "2026-07-13T11:00:00.000+03:00",
      running: false,
    })
    await client.markAllNotificationsRead({
      workerId: warning.workerId,
      notifications: [{ id: warning.id, expectedVersion: warning.version }],
    })
    const notifications = await client.listNotifications(warning.workerId)
    expect(
      notifications.find((item) => item.id === warning.id)?.readAt
    ).not.toBeNull()
    expect(
      notifications.find((item) => item.type === "BREAK_STARTED")?.readAt
    ).toBeNull()
    await expect(
      client.markAllNotificationsRead({
        workerId: warning.workerId,
        notifications: [{ id: warning.id, expectedVersion: warning.version }],
      })
    ).rejects.toMatchObject({ status: 409 })
  })

  it("does not resume a manually paused task when a schedule pause ends", async () => {
    const snapshot = await client.getSnapshot(SPB_WAREHOUSE_ID)
    const task = snapshot.tasks.find(
      (candidate) => candidate.externalTaskId === "demo:internal_works:1"
    )!
    await client.pauseTask({
      taskId: task.id,
      expectedVersion: task.version,
    })
    let clock = await client.getSimulationClock()
    clock = await client.updateSimulationClock({
      expectedVersion: clock.version,
      now: "2026-07-13T11:02:00.000+03:00",
      running: false,
    })
    let current = (await client.getSnapshot(SPB_WAREHOUSE_ID)).tasks.find(
      (candidate) => candidate.id === task.id
    )!
    expect(current.pauseReasons.map((reason) => reason.type).sort()).toEqual([
      "MANUAL",
      "SCHEDULE",
    ])

    await client.updateSimulationClock({
      expectedVersion: clock.version,
      now: "2026-07-13T11:11:00.000+03:00",
      running: false,
    })
    current = (await client.getSnapshot(SPB_WAREHOUSE_ID)).tasks.find(
      (candidate) => candidate.id === task.id
    )!
    expect(current.status).toBe("PAUSED")
    expect(current.pauseReasons.map((reason) => reason.type)).toEqual([
      "MANUAL",
    ])

    expect(
      await client.resumeTask({
        taskId: current.id,
        expectedVersion: current.version,
      })
    ).toMatchObject({ status: "IN_PROGRESS", pauseReasons: [] })
  })
})
