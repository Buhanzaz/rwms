import { HugeiconsIcon } from "@hugeicons/react"
import { Notification02Icon } from "@hugeicons/core-free-icons"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Popover,
  PopoverContent,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "@/components/ui/popover"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import type { WorkerDto } from "@/features/settings/task-board/model/task-board-settings"
import type { WorkerNotificationDto } from "@/features/task-board/mock/model"

function notificationTime(value: string) {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime())
    ? "Время не указано"
    : new Intl.DateTimeFormat("ru-RU", {
        day: "2-digit",
        month: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
      }).format(parsed)
}

export function TaskBoardMockToolbar({
  workers,
  activeWorkerId,
  notifications,
  onWorkerChange,
  onMarkRead,
  onMarkAllRead,
}: {
  workers: WorkerDto[]
  activeWorkerId: string | null
  notifications: WorkerNotificationDto[]
  onWorkerChange: (workerId: string) => void
  onMarkRead: (notification: WorkerNotificationDto) => void
  onMarkAllRead: () => void
}) {
  const unread = notifications.filter((notification) => !notification.readAt)

  return (
    <div className="flex min-w-0 items-center gap-2">
      <Select value={activeWorkerId ?? ""} onValueChange={onWorkerChange}>
        <SelectTrigger
          className="min-w-44 flex-1 sm:w-56 sm:flex-none"
          aria-label="Рабочий для демонстрации уведомлений"
        >
          <SelectValue placeholder="Выберите рабочего" />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            {workers.map((worker) => (
              <SelectItem key={worker.id} value={worker.id}>
                {worker.displayName}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>

      <Popover>
        <PopoverTrigger asChild>
          <Button
            type="button"
            size="icon"
            variant="outline"
            className="relative"
            aria-label={`Уведомления рабочего, непрочитанных: ${unread.length}`}
          >
            <HugeiconsIcon icon={Notification02Icon} />
            {unread.length > 0 ? (
              <Badge
                variant="destructive"
                className="absolute -end-2 -top-2 min-w-5 justify-center px-1"
              >
                {unread.length > 99 ? "99+" : unread.length}
              </Badge>
            ) : null}
          </Button>
        </PopoverTrigger>
        <PopoverContent
          align="end"
          className="w-80 max-w-[calc(100vw-2rem)] p-0"
        >
          <PopoverHeader className="flex-row items-center justify-between gap-3 px-3 pt-3">
            <PopoverTitle>Входящие</PopoverTitle>
            {unread.length > 0 ? (
              <Button
                type="button"
                size="xs"
                variant="ghost"
                onClick={onMarkAllRead}
              >
                Прочитать все
              </Button>
            ) : null}
          </PopoverHeader>
          <div className="max-h-80 overflow-y-auto border-t p-1">
            {notifications.length === 0 ? (
              <p className="px-2 py-6 text-center text-sm text-muted-foreground">
                Уведомлений пока нет
              </p>
            ) : (
              notifications.map((notification) => (
                <Button
                  key={notification.id}
                  type="button"
                  variant="ghost"
                  className="h-auto w-full flex-col items-start gap-1 px-2 py-2 text-start whitespace-normal"
                  onClick={() => onMarkRead(notification)}
                >
                  <span className="flex w-full items-start gap-2">
                    {!notification.readAt ? (
                      <span
                        className="mt-1.5 size-2 shrink-0 rounded-full bg-primary"
                        aria-label="Непрочитанное"
                      />
                    ) : null}
                    <span className="min-w-0 flex-1">
                      {notification.message}
                    </span>
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {notificationTime(notification.createdAt)}
                  </span>
                </Button>
              ))
            )}
          </div>
        </PopoverContent>
      </Popover>
    </div>
  )
}
