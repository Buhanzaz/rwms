import type { TaskBoardQueueDto } from "@/features/task-board/model/task-board"

export function mergeQueueCollapsedSettings(params: {
  current: Set<string>
  previous: Map<string, boolean> | null
  queues: TaskBoardQueueDto[]
  reset: boolean
}) {
  const settings = new Map(
    params.queues.map((queue) => [queue.key, queue.settingsCollapsed])
  )
  if (params.reset || !params.previous) {
    return {
      collapsed: new Set(
        [...settings]
          .filter(([, collapsed]) => collapsed)
          .map(([queueKey]) => queueKey)
      ),
      settings,
    }
  }
  const collapsed = new Set(params.current)
  settings.forEach((next, queueKey) => {
    const previous = params.previous?.get(queueKey)
    if (previous === undefined || previous === next) return
    if (next) collapsed.add(queueKey)
    else collapsed.delete(queueKey)
  })
  return { collapsed, settings }
}
