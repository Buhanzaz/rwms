import type {
  GroupScheduleDto,
  QueueType,
  RestPeriodDto,
  WorkerClassDto,
  WorkerDto,
  WorkerGroupDto,
  WorkQueueDto,
} from "@/features/settings/task-board/model/task-board-settings"
import type {
  MockTaskAssignmentDto,
  MockTaskBoardEnvelope,
  MockTaskDto,
} from "@/features/task-board/mock/model"

export const TASK_BOARD_MOCK_STORAGE_KEY = "rwms.task-board.browser-mock.v2"
export const TASK_BOARD_MOCK_LOCK_NAME = "rwms.task-board.browser-mock.mutation"
export const TASK_BOARD_MOCK_CHANGE_EVENT = "rwms:task-board-mock-change"
export const TASK_BOARD_MOCK_SCHEMA_VERSION = 2
export const TASK_BOARD_MOCK_SEED_VERSION = 1

export const SPB_WAREHOUSE_ID = "00000000-0000-0000-0000-000000000001"
export const MSK_WAREHOUSE_ID = "00000000-0000-0000-0000-000000000002"

const CLASS_IDS = {
  DRIVER: "10000000-0000-0000-0000-000000000001",
  GENERAL_WORKER: "10000000-0000-0000-0000-000000000002",
  RIGGER: "10000000-0000-0000-0000-000000000003",
  ELECTRICIAN: "10000000-0000-0000-0000-000000000004",
  PLUMBER: "10000000-0000-0000-0000-000000000005",
  WELDER: "10000000-0000-0000-0000-000000000006",
  SES: "10000000-0000-0000-0000-000000000007",
} as const

export const TASK_BOARD_MOCK_GROUP_IDS = {
  DRIVERS: "30000000-0000-0000-0000-000000000001",
  GENERAL_1: "30000000-0000-0000-0000-000000000002",
  GENERAL_2: "30000000-0000-0000-0000-000000000003",
  ELECTRICIANS_1: "30000000-0000-0000-0000-000000000004",
  ELECTRICIANS_2: "30000000-0000-0000-0000-000000000005",
  PLUMBERS_1: "30000000-0000-0000-0000-000000000006",
  PLUMBERS_2: "30000000-0000-0000-0000-000000000007",
  WELDERS: "30000000-0000-0000-0000-000000000008",
  SES: "30000000-0000-0000-0000-000000000009",
} as const

const classSeed: WorkerClassDto[] = [
  ["DRIVER", "Водители"],
  ["GENERAL_WORKER", "Разнорабочие"],
  ["RIGGER", "Стропальщики"],
  ["ELECTRICIAN", "Электрики"],
  ["PLUMBER", "Сантехники"],
  ["WELDER", "Сварщики"],
  ["SES", "СЭС"],
].map(([code, name], index) => ({
  id: CLASS_IDS[code as keyof typeof CLASS_IDS],
  version: 0,
  code,
  name,
  description: null,
  comment: null,
  sortOrder: (index + 1) * 10,
  active: true,
}))

type WorkerSeed = {
  id: string
  displayName: string
  firstName: string
  lastName: string
  login: string
  classCodes: (keyof typeof CLASS_IDS)[]
}

const workerSeeds: WorkerSeed[] = [
  {
    id: "20000000-0000-0000-0000-000000000001",
    displayName: "Алексей Соколов",
    firstName: "Алексей",
    lastName: "Соколов",
    login: "a.sokolov",
    classCodes: ["DRIVER"],
  },
  {
    id: "20000000-0000-0000-0000-000000000002",
    displayName: "Дмитрий Орлов",
    firstName: "Дмитрий",
    lastName: "Орлов",
    login: "d.orlov",
    classCodes: ["DRIVER"],
  },
  ...[
    ["Иван Петров", "Иван", "Петров"],
    ["Сергей Волков", "Сергей", "Волков"],
    ["Николай Кузнецов", "Николай", "Кузнецов"],
    ["Артём Лебедев", "Артём", "Лебедев"],
  ].map(([displayName, firstName, lastName], index) => ({
    id: `20000000-0000-0000-0000-${String(index + 3).padStart(12, "0")}`,
    displayName,
    firstName,
    lastName,
    login: `general.${index + 1}`,
    classCodes: ["GENERAL_WORKER", "RIGGER"] as (keyof typeof CLASS_IDS)[],
  })),
  ...[
    ["Павел Морозов", "Павел", "Морозов"],
    ["Максим Новиков", "Максим", "Новиков"],
    ["Андрей Фёдоров", "Андрей", "Фёдоров"],
    ["Виктор Смирнов", "Виктор", "Смирнов"],
  ].map(([displayName, firstName, lastName], index) => ({
    id: `20000000-0000-0000-0000-${String(index + 7).padStart(12, "0")}`,
    displayName,
    firstName,
    lastName,
    login: `electrician.${index + 1}`,
    classCodes: ["ELECTRICIAN"] as (keyof typeof CLASS_IDS)[],
  })),
  ...[
    ["Олег Васильев", "Олег", "Васильев"],
    ["Роман Павлов", "Роман", "Павлов"],
    ["Илья Семёнов", "Илья", "Семёнов"],
    ["Егор Виноградов", "Егор", "Виноградов"],
  ].map(([displayName, firstName, lastName], index) => ({
    id: `20000000-0000-0000-0000-${String(index + 11).padStart(12, "0")}`,
    displayName,
    firstName,
    lastName,
    login: `plumber.${index + 1}`,
    classCodes: ["PLUMBER"] as (keyof typeof CLASS_IDS)[],
  })),
  ...[
    ["Антон Попов", "Антон", "Попов"],
    ["Михаил Козлов", "Михаил", "Козлов"],
  ].map(([displayName, firstName, lastName], index) => ({
    id: `20000000-0000-0000-0000-${String(index + 15).padStart(12, "0")}`,
    displayName,
    firstName,
    lastName,
    login: `welder.${index + 1}`,
    classCodes: ["WELDER"] as (keyof typeof CLASS_IDS)[],
  })),
  ...[
    ["Мария Белова", "Мария", "Белова"],
    ["Елена Захарова", "Елена", "Захарова"],
  ].map(([displayName, firstName, lastName], index) => ({
    id: `20000000-0000-0000-0000-${String(index + 17).padStart(12, "0")}`,
    displayName,
    firstName,
    lastName,
    login: `ses.${index + 1}`,
    classCodes: ["SES"] as (keyof typeof CLASS_IDS)[],
  })),
]

function workerClass(code: keyof typeof CLASS_IDS) {
  return classSeed.find((item) => item.code === code)!
}

const workers: WorkerDto[] = workerSeeds.map((worker) => ({
  id: worker.id,
  version: 0,
  warehouseId: SPB_WAREHOUSE_ID,
  displayName: worker.displayName,
  firstName: worker.firstName,
  lastName: worker.lastName,
  middleName: null,
  active: true,
  comment: null,
  appLogin: worker.login,
  credentialStatus: "ACTIVE",
  credentialError: null,
  qualifications: worker.classCodes.map((code) => ({
    id: `${worker.id}:qualification:${code}`,
    version: 0,
    workerClass: workerClass(code),
    active: true,
    comment: null,
  })),
}))

type GroupSeed = {
  id: string
  name: string
  classCode: keyof typeof CLASS_IDS
  workerIndexes: number[]
}

const groupSeeds: GroupSeed[] = [
  {
    id: TASK_BOARD_MOCK_GROUP_IDS.DRIVERS,
    name: "Водители",
    classCode: "DRIVER",
    workerIndexes: [0, 1],
  },
  {
    id: TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1,
    name: "Разнорабочие 1",
    classCode: "GENERAL_WORKER",
    workerIndexes: [2, 3],
  },
  {
    id: TASK_BOARD_MOCK_GROUP_IDS.GENERAL_2,
    name: "Разнорабочие 2",
    classCode: "GENERAL_WORKER",
    workerIndexes: [4, 5],
  },
  {
    id: TASK_BOARD_MOCK_GROUP_IDS.ELECTRICIANS_1,
    name: "Электрики 1",
    classCode: "ELECTRICIAN",
    workerIndexes: [6, 7],
  },
  {
    id: TASK_BOARD_MOCK_GROUP_IDS.ELECTRICIANS_2,
    name: "Электрики 2",
    classCode: "ELECTRICIAN",
    workerIndexes: [8, 9],
  },
  {
    id: TASK_BOARD_MOCK_GROUP_IDS.PLUMBERS_1,
    name: "Сантехники 1",
    classCode: "PLUMBER",
    workerIndexes: [10, 11],
  },
  {
    id: TASK_BOARD_MOCK_GROUP_IDS.PLUMBERS_2,
    name: "Сантехники 2",
    classCode: "PLUMBER",
    workerIndexes: [12, 13],
  },
  {
    id: TASK_BOARD_MOCK_GROUP_IDS.WELDERS,
    name: "Сварщики",
    classCode: "WELDER",
    workerIndexes: [14, 15],
  },
  {
    id: TASK_BOARD_MOCK_GROUP_IDS.SES,
    name: "СЭС",
    classCode: "SES",
    workerIndexes: [16, 17],
  },
]

const groups: WorkerGroupDto[] = groupSeeds.map((group) => ({
  id: group.id,
  version: 0,
  warehouseId: SPB_WAREHOUSE_ID,
  workerClass: workerClass(group.classCode),
  name: group.name,
  description: null,
  active: true,
  members: group.workerIndexes.map((workerIndex) => ({
    id: `${group.id}:member:${workers[workerIndex].id}`,
    version: 0,
    workerId: workers[workerIndex].id,
    workerName: workers[workerIndex].displayName,
    roleInGroup: null,
    active: true,
  })),
}))

const queueSeeds = [
  ["MOVEMENT", "Перемещение", "MOVEMENT", "GENERAL_WORKER", true],
  ["INTERNAL_WORKS", "Внутренние работы", "REPAIR", "GENERAL_WORKER", false],
  ["EXTERNAL_WORKS", "Внешние работы", "REPAIR", "GENERAL_WORKER", false],
  ["ELECTRICS", "Электрика", "REPAIR", "ELECTRICIAN", false],
  ["PLUMBING", "Сантехника", "REPAIR", "PLUMBER", false],
  ["WELDING", "Сварка", "REPAIR", "WELDER", false],
  ["SANITARY_DISINFECTION", "СЭС и санитария", "REPAIR", "SES", false],
  ["HOLDING", "Ожидание", "HOLDING", null, false],
] as const

function createQueues(warehouseId: string, warehouseSequence: number) {
  return queueSeeds.map(
    ([code, name, type, classCode, stopTaskOnTake], index) => {
      const id = `50000000-0000-0000-0000-${String(warehouseSequence * 100 + index + 1).padStart(12, "0")}`
      const linkedClass = classCode ? workerClass(classCode) : null
      return {
        id,
        version: 0,
        warehouseId,
        code,
        name,
        description: null,
        type: type as QueueType,
        sortOrder: (index + 1) * 10,
        active: true,
        hidden: false,
        collapsed: code === "HOLDING",
        holdingPeriodMinutes: code === "HOLDING" ? 30 : null,
        notificationThreshold: null,
        notifyWhenThresholdReached: false,
        bindings: linkedClass
          ? [
              {
                id: `${id}:binding:${linkedClass.id}`,
                version: 0,
                workerClass: linkedClass,
                stopTaskOnTake,
              },
            ]
          : [],
      } satisfies WorkQueueDto
    }
  )
}

const queues = [
  ...createQueues(SPB_WAREHOUSE_ID, 1),
  ...createQueues(MSK_WAREHOUSE_ID, 2),
]

function defaultRestPeriods(scheduleId: string, day: number): RestPeriodDto[] {
  return [
    ["SMOKE_BREAK", "11:00", "11:10"],
    ["LUNCH", "13:00", "14:00"],
    ["SMOKE_BREAK", "16:00", "16:10"],
  ].map(([type, startsAt, endsAt], index) => ({
    id: `${scheduleId}:day:${day}:rest:${index + 1}`,
    version: 0,
    type: type as RestPeriodDto["type"],
    startsAt,
    endsAt,
    warningMinutes: 5,
    autoPause: true,
  }))
}

const schedules: GroupScheduleDto[] = groups.map((group, index) => {
  const id = `40000000-0000-0000-0000-${String(index + 1).padStart(12, "0")}`
  return {
    id,
    version: 0,
    warehouseId: SPB_WAREHOUSE_ID,
    workerGroupId: group.id,
    timezone: "Europe/Moscow",
    returnGraceMinutes: 3,
    days: [1, 2, 3, 4, 5, 6, 7].map((day) => ({
      dayOfWeek: day as 1 | 2 | 3 | 4 | 5 | 6 | 7,
      enabled: day <= 5,
      linkedToTemplate: day <= 5,
      shiftStartsAt: "09:00",
      shiftEndsAt: "18:00",
      restPeriods: day <= 5 ? defaultRestPeriods(id, day) : [],
    })),
  }
})

function queueId(code: string) {
  return queues.find(
    (queue) => queue.warehouseId === SPB_WAREHOUSE_ID && queue.code === code
  )!.id
}

const DEMO_AT = new Date().toISOString()
const DEMO_CREATED_AT = new Date(
  Date.parse(DEMO_AT) - 30 * 60_000
).toISOString()

function demoTask(
  sequence: number,
  code: string,
  title: string,
  cabinNumber: string,
  status: MockTaskDto["status"],
  groupId: string | null,
  route: { queueCode: string; title: string }[] = []
): { task: MockTaskDto; assignment: MockTaskAssignmentDto | null } {
  const id = `70000000-0000-0000-0000-${String(sequence).padStart(12, "0")}`
  const group = groups.find((candidate) => candidate.id === groupId)
  const assignmentId = group
    ? `80000000-0000-0000-0000-${String(sequence).padStart(12, "0")}`
    : null
  return {
    task: {
      id,
      version: 0,
      externalTaskId: `demo:${code.toLocaleLowerCase()}:${sequence}`,
      warehouseId: SPB_WAREHOUSE_ID,
      title,
      description: "Демонстрационное задание browser MOCK",
      cabinNumber,
      queueId: queueId(code),
      queueCode: code,
      queuePosition: sequence * 10,
      status,
      demo: true,
      route: (route.length ? route : [{ queueCode: code, title }]).map(
        (step, index) => ({
          id: `${id}:route:${index + 1}`,
          externalStepId: null,
          ...step,
          position: index,
        })
      ),
      activeRouteIndex: 0,
      assignmentId,
      pauseReasons: [],
      elapsedSeconds: status === "IN_PROGRESS" ? 1_800 : 0,
      activeSince: status === "IN_PROGRESS" ? DEMO_AT : null,
      createdAt: DEMO_CREATED_AT,
      updatedAt: DEMO_AT,
    },
    assignment: group
      ? {
          id: assignmentId!,
          version: 0,
          taskId: id,
          workerGroupId: group.id,
          workerIds: group.members.map((member) => member.workerId),
          status: status === "IN_PROGRESS" ? "ACTIVE" : "PAUSED",
          startedAt: DEMO_AT,
          endedAt: null,
        }
      : null,
  }
}

const demo = [
  demoTask(
    1,
    "INTERNAL_WORKS",
    "Внутренняя отделка",
    "БЫТ-001",
    "IN_PROGRESS",
    TASK_BOARD_MOCK_GROUP_IDS.GENERAL_1
  ),
  demoTask(2, "MOVEMENT", "Переместить мебель", "БЫТ-002", "QUEUED", null),
  demoTask(3, "ELECTRICS", "Проверить электрику", "БЫТ-003", "QUEUED", null),
  demoTask(4, "PLUMBING", "Заменить смеситель", "БЫТ-004", "QUEUED", null),
  demoTask(5, "WELDING", "Ремонт каркаса", "БЫТ-005", "QUEUED", null),
  demoTask(
    6,
    "SANITARY_DISINFECTION",
    "Санитарная обработка",
    "БЫТ-006",
    "QUEUED",
    null
  ),
  demoTask(
    7,
    "INTERNAL_WORKS",
    "Многоэтапный ремонт",
    "БЫТ-007",
    "QUEUED",
    null,
    [
      { queueCode: "INTERNAL_WORKS", title: "Внутренние работы" },
      { queueCode: "ELECTRICS", title: "Электрика" },
      { queueCode: "HOLDING", title: "Ожидание" },
    ]
  ),
]

export function createTaskBoardMockSeed(): MockTaskBoardEnvelope {
  return {
    service: "task-board-browser-mock",
    schemaVersion: TASK_BOARD_MOCK_SCHEMA_VERSION,
    seedVersion: TASK_BOARD_MOCK_SEED_VERSION,
    revision: 0,
    queues: structuredClone(queues),
    classes: structuredClone(classSeed),
    workers: structuredClone(workers),
    groups: structuredClone(groups),
    schedules: structuredClone(schedules),
    tasks: demo.map((item) => structuredClone(item.task)),
    assignments: demo.flatMap((item) =>
      item.assignment ? [structuredClone(item.assignment)] : []
    ),
    interruptions: [],
    notifications: [],
    clock: {
      version: 0,
      mode: "SIMULATION",
      running: false,
      speed: 60,
      anchorSimulatedAt: DEMO_AT,
      anchorRealAt: new Date().toISOString(),
      activeWorkerId: workers[2].id,
    },
    auditEvents: [],
    tombstones: [],
  }
}

export const taskBoardMockSeedIds = {
  classes: CLASS_IDS,
  groups: TASK_BOARD_MOCK_GROUP_IDS,
}
