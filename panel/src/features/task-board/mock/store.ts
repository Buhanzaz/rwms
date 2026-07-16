import type { MockTaskBoardEnvelope } from "@/features/task-board/mock/model"
import {
  createTaskBoardMockSeed,
  TASK_BOARD_MOCK_CHANGE_EVENT,
  TASK_BOARD_MOCK_LOCK_NAME,
  TASK_BOARD_MOCK_SCHEMA_VERSION,
  TASK_BOARD_MOCK_SEED_VERSION,
  TASK_BOARD_MOCK_STORAGE_KEY,
} from "@/features/task-board/mock/seed"

type Mutation<T> = (draft: MockTaskBoardEnvelope) => T | Promise<T>

let fallbackQueue: Promise<unknown> = Promise.resolve()
let memoryEnvelope: MockTaskBoardEnvelope | null = null

function clone<T>(value: T): T {
  return structuredClone(value)
}

function hasBrowserStorage() {
  return (
    typeof window !== "undefined" && typeof window.localStorage !== "undefined"
  )
}

function mergeById<T extends { id: string }>(
  current: T[] | undefined,
  seeded: T[],
  tombstones: Set<string>
) {
  const result = [...(current ?? [])]
  const existingIds = new Set(result.map((item) => item.id))
  for (const item of seeded) {
    if (!existingIds.has(item.id) && !tombstones.has(item.id)) {
      result.push(clone(item))
    }
  }
  return result
}

function migrate(value: unknown): MockTaskBoardEnvelope {
  const seed = createTaskBoardMockSeed()
  if (!value || typeof value !== "object") return seed
  const source = value as Omit<
    Partial<MockTaskBoardEnvelope>,
    "schemaVersion"
  > & {
    schemaVersion?: number
  }
  if (
    source.service !== "task-board-browser-mock" ||
    (source.schemaVersion !== 1 &&
      source.schemaVersion !== TASK_BOARD_MOCK_SCHEMA_VERSION)
  ) {
    return seed
  }

  const tombstones = new Set(
    Array.isArray(source.tombstones) ? source.tombstones : []
  )
  const result: MockTaskBoardEnvelope = {
    ...seed,
    revision:
      typeof source.revision === "number" && source.revision >= 0
        ? source.revision
        : 0,
    queues: mergeById(source.queues, seed.queues, tombstones),
    classes: mergeById(source.classes, seed.classes, tombstones),
    workers: mergeById(source.workers, seed.workers, tombstones),
    groups: mergeById(source.groups, seed.groups, tombstones),
    schedules: mergeById(source.schedules, seed.schedules, tombstones),
    tasks: mergeById(source.tasks, seed.tasks, tombstones),
    assignments: mergeById(source.assignments, seed.assignments, tombstones),
    interruptions: Array.isArray(source.interruptions)
      ? clone(source.interruptions)
      : [],
    notifications: Array.isArray(source.notifications)
      ? clone(source.notifications)
      : [],
    clock:
      source.clock && typeof source.clock === "object"
        ? { ...seed.clock, ...clone(source.clock) }
        : seed.clock,
    auditEvents: Array.isArray(source.auditEvents)
      ? clone(source.auditEvents)
      : [],
    tombstones: [...tombstones],
    schemaVersion: TASK_BOARD_MOCK_SCHEMA_VERSION,
    seedVersion: TASK_BOARD_MOCK_SEED_VERSION,
  }
  return result
}

function loadEnvelope() {
  if (!hasBrowserStorage()) {
    memoryEnvelope ??= createTaskBoardMockSeed()
    return clone(memoryEnvelope)
  }
  const raw = window.localStorage.getItem(TASK_BOARD_MOCK_STORAGE_KEY)
  if (!raw) {
    const seed = createTaskBoardMockSeed()
    window.localStorage.setItem(
      TASK_BOARD_MOCK_STORAGE_KEY,
      JSON.stringify(seed)
    )
    return seed
  }
  try {
    const migrated = migrate(JSON.parse(raw) as unknown)
    const serialized = JSON.stringify(migrated)
    if (serialized !== raw) {
      window.localStorage.setItem(TASK_BOARD_MOCK_STORAGE_KEY, serialized)
    }
    return migrated
  } catch {
    const seed = createTaskBoardMockSeed()
    window.localStorage.setItem(
      TASK_BOARD_MOCK_STORAGE_KEY,
      JSON.stringify(seed)
    )
    return seed
  }
}

function persistEnvelope(envelope: MockTaskBoardEnvelope) {
  if (!hasBrowserStorage()) {
    memoryEnvelope = clone(envelope)
    return
  }
  window.localStorage.setItem(
    TASK_BOARD_MOCK_STORAGE_KEY,
    JSON.stringify(envelope)
  )
  window.dispatchEvent(
    new CustomEvent(TASK_BOARD_MOCK_CHANGE_EVENT, {
      detail: { revision: envelope.revision },
    })
  )
}

async function withMutationLock<T>(operation: () => Promise<T>): Promise<T> {
  if (typeof navigator !== "undefined" && navigator.locks) {
    return navigator.locks.request(TASK_BOARD_MOCK_LOCK_NAME, operation)
  }
  const queued = fallbackQueue.then(operation, operation)
  fallbackQueue = queued.then(
    () => undefined,
    () => undefined
  )
  return queued
}

export class BrowserTaskBoardStore {
  read() {
    const envelope = loadEnvelope()
    if (
      envelope.schemaVersion !== TASK_BOARD_MOCK_SCHEMA_VERSION ||
      envelope.seedVersion !== TASK_BOARD_MOCK_SEED_VERSION
    ) {
      throw new Error("Не удалось обновить browser MOCK доски задач.")
    }
    return clone(envelope)
  }

  async mutate<T>(mutation: Mutation<T>) {
    return withMutationLock(async () => {
      const draft = loadEnvelope()
      const before = JSON.stringify(draft)
      const result = await mutation(draft)
      if (JSON.stringify(draft) === before) return clone(result)
      draft.revision += 1
      persistEnvelope(draft)
      return clone(result)
    })
  }

  subscribe(listener: () => void) {
    if (typeof window === "undefined") return () => undefined
    const onCustomEvent = () => listener()
    const onStorage = (event: StorageEvent) => {
      if (event.key === TASK_BOARD_MOCK_STORAGE_KEY) listener()
    }
    window.addEventListener(TASK_BOARD_MOCK_CHANGE_EVENT, onCustomEvent)
    window.addEventListener("storage", onStorage)
    return () => {
      window.removeEventListener(TASK_BOARD_MOCK_CHANGE_EVENT, onCustomEvent)
      window.removeEventListener("storage", onStorage)
    }
  }

  resetForTests() {
    memoryEnvelope = null
    if (hasBrowserStorage()) {
      window.localStorage.removeItem(TASK_BOARD_MOCK_STORAGE_KEY)
    }
  }
}
