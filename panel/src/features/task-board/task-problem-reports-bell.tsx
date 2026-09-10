import { useCallback, useEffect, useRef, useState } from "react"
import {
  useInfiniteQuery,
  useMutation,
  useQueryClient,
} from "@tanstack/react-query"
import { BellDotIcon, BellIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { FullscreenPhotoViewer } from "@/components/media/fullscreen-photo-viewer"
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "@/components/ui/popover"
import {
  listTaskProblemReports,
  markTaskProblemReportRead,
  applyTaskProblemReportToAll,
  taskProblemReportsQueryKey,
  type TaskProblemReportAttachment,
} from "@/features/task-board/api/task-problem-reports-api"
import { acquireMediaPreview } from "@/features/media/media-preview-cache"
import type { DisposableMediaObjectUrl } from "@/features/media/media-service"
import {
  apiErrorFromRequestFailure,
  apiErrorFromResponse,
} from "@/lib/api-client"

const attachmentState = {
  RESERVED: "Фото ожидает загрузки",
  UPLOADING: "Фото загружается",
  READY: "Фото готово",
  REVIEW_REQUIRED: "Фото требует проверки",
  REJECTED: "Фото отклонено",
} as const

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(value))
}

function ProtectedThumbnail({
  accessToken,
  warehouseId,
  userId,
  attachment,
}: {
  accessToken: string
  warehouseId: string
  userId: string
  attachment: TaskProblemReportAttachment
}) {
  const [url, setUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [viewerOpen, setViewerOpen] = useState(false)
  const [fullUrl, setFullUrl] = useState<string | null>(null)
  const [fullError, setFullError] = useState<Error | null>(null)
  const [fullRequest, setFullRequest] = useState(0)

  const loadMedia = useCallback(
    async (
      path: string,
      signal: AbortSignal
    ): Promise<DisposableMediaObjectUrl> => {
      const url = new URL(path, window.location.origin)
      if (
        url.origin !== window.location.origin ||
        !url.pathname.startsWith("/api/media/")
      ) {
        throw new Error("Некорректный путь к фото")
      }
      let response: Response
      try {
        response = await fetch(url, {
          headers: {
            Authorization: `Bearer ${accessToken}`,
            Accept: "image/*",
          },
          signal,
        })
      } catch (error) {
        throw apiErrorFromRequestFailure(error)
      }
      if (!response.ok) throw await apiErrorFromResponse(response)
      const blob = await response.blob()
      if (!blob.type.startsWith("image/")) throw new Error("Некорректное фото")
      const objectUrl = URL.createObjectURL(blob)
      return {
        url: objectUrl,
        contentType: blob.type,
        size: blob.size,
        dispose: () => URL.revokeObjectURL(objectUrl),
      }
    },
    [accessToken]
  )

  const acquire = useCallback(
    (path: string, variant: string, signal: AbortSignal) => {
      return acquireMediaPreview(
        `${warehouseId}:${userId}:${attachment.mediaId ?? attachment.evidenceId}:${attachment.mediaGeneration ?? 0}:${variant}`,
        (loaderSignal) => loadMedia(path, loaderSignal),
        { signal }
      )
    },
    [
      attachment.evidenceId,
      attachment.mediaGeneration,
      attachment.mediaId,
      loadMedia,
      userId,
      warehouseId,
    ]
  )

  useEffect(() => {
    if (attachment.state !== "READY" || !attachment.thumbnailPath) return
    const controller = new AbortController()
    let release: (() => void) | null = null
    void acquire(attachment.thumbnailPath, "thumbnail", controller.signal)
      .then((lease) => {
        release = lease.release
        setFailed(false)
        setUrl(lease.url)
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true)
      })
    return () => {
      controller.abort()
      release?.()
    }
  }, [acquire, attachment.state, attachment.thumbnailPath])

  useEffect(() => {
    if (!viewerOpen || attachment.state !== "READY" || !attachment.readPath)
      return
    const controller = new AbortController()
    let release: (() => void) | null = null
    void acquire(attachment.readPath, "original", controller.signal)
      .then((lease) => {
        release = lease.release
        setFullUrl(lease.url)
      })
      .catch(() => {
        if (!controller.signal.aborted)
          setFullError(new Error("Не удалось загрузить полноразмерное фото."))
      })
    return () => {
      controller.abort()
      release?.()
    }
  }, [acquire, attachment.state, attachment.readPath, fullRequest, viewerOpen])

  const viewerError = !attachment.readPath
    ? new Error("Полноразмерное фото недоступно.")
    : fullError

  if (attachment.state !== "READY" || !attachment.thumbnailPath) {
    return <Badge variant="outline">{attachmentState[attachment.state]}</Badge>
  }
  if (failed) return <Badge variant="destructive">Фото недоступно</Badge>
  if (!url) return <Badge variant="muted">Загрузка фото</Badge>
  return (
    <>
      <button
        type="button"
        className="rounded-md focus-visible:ring-[3px] focus-visible:ring-ring/50"
        onClick={() => {
          setFullUrl(null)
          setFullError(null)
          setViewerOpen(true)
        }}
        aria-label="Открыть фото проблемы"
      >
        <img
          className="size-16 rounded-md border object-cover"
          src={url}
          alt="Фото проблемы"
        />
      </button>
      <FullscreenPhotoViewer
        photos={
          fullUrl
            ? [
                {
                  id: attachment.evidenceId,
                  src: fullUrl,
                  alt: "Фото проблемы",
                },
              ]
            : []
        }
        open={viewerOpen}
        title="Фото проблемы"
        loading={viewerOpen && !fullUrl && !viewerError}
        error={viewerError}
        onRetry={() => {
          setFullUrl(null)
          setFullError(null)
          setFullRequest((request) => request + 1)
        }}
        onOpenChange={setViewerOpen}
      />
    </>
  )
}

export function TaskProblemReportsBell({
  accessToken,
  warehouseId,
  userId,
  canApplyToAll = false,
}: {
  accessToken: string | null
  warehouseId: string | null
  userId: string | null
  canApplyToAll?: boolean
}) {
  const [open, setOpen] = useState(false)
  const [readErrorReportId, setReadErrorReportId] = useState<string | null>(
    null
  )
  const [applyErrorReportId, setApplyErrorReportId] = useState<string | null>(
    null
  )
  const applyOperationIds = useRef(new Map<string, string>())
  const queryClient = useQueryClient()
  const queryKey = taskProblemReportsQueryKey(
    warehouseId ?? "none",
    userId ?? "none"
  )
  const reportsQuery = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam, signal }) =>
      listTaskProblemReports(accessToken!, warehouseId!, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    enabled: Boolean(accessToken && warehouseId && userId),
    refetchInterval: 15_000,
  })
  const markRead = useMutation({
    mutationFn: (reportId: string) =>
      markTaskProblemReportRead(accessToken!, warehouseId!, reportId),
    onSuccess: () => {
      setReadErrorReportId(null)
      return queryClient.invalidateQueries({ queryKey })
    },
    onError: (_, reportId) => setReadErrorReportId(reportId),
  })
  const applyToAll = useMutation({
    mutationFn: ({
      reportId,
      operationId,
    }: {
      reportId: string
      operationId: string
    }) =>
      applyTaskProblemReportToAll(
        accessToken!,
        warehouseId!,
        reportId,
        operationId
      ),
    onSuccess: async (_, variables) => {
      applyOperationIds.current.delete(variables.reportId)
      setApplyErrorReportId(null)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey }),
        queryClient.invalidateQueries({
          queryKey: ["task-board", warehouseId],
        }),
      ])
    },
    onError: (_, variables) => setApplyErrorReportId(variables.reportId),
  })
  const reports = reportsQuery.data?.pages.flatMap((page) => page.reports) ?? []
  const unreadCount = reportsQuery.data?.pages[0]?.unreadCount ?? 0

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          size="icon-sm"
          variant="ghost"
          className="relative"
          aria-label={
            unreadCount
              ? `Сообщения рабочих: ${unreadCount} непрочитанных`
              : "Сообщения рабочих"
          }
        >
          <HugeiconsIcon
            icon={unreadCount ? BellDotIcon : BellIcon}
            aria-hidden="true"
          />
          {unreadCount ? (
            <span className="absolute -top-1 -right-1 min-w-4 rounded-full bg-destructive px-1 text-center text-[10px] leading-4 text-primary-foreground">
              {unreadCount > 99 ? "99+" : unreadCount}
            </span>
          ) : null}
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align="end"
        className="w-[min(24rem,calc(100vw-2rem))] gap-3 p-3"
      >
        <PopoverHeader>
          <PopoverTitle>Сообщения рабочих</PopoverTitle>
          <PopoverDescription>
            {warehouseId
              ? "Проблемы по задачам выбранного склада"
              : "Выберите склад, чтобы увидеть сообщения."}
          </PopoverDescription>
        </PopoverHeader>
        {!accessToken || !userId ? (
          <p className="text-sm text-muted-foreground">
            Нет защищённого сеанса для сообщений.
          </p>
        ) : !warehouseId ? null : reportsQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Загружаем сообщения…</p>
        ) : reportsQuery.isError ? (
          <div className="flex flex-col gap-2 text-sm">
            <p className="text-destructive">Не удалось загрузить сообщения.</p>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => reportsQuery.refetch()}
            >
              Повторить
            </Button>
          </div>
        ) : reports.length === 0 ? (
          <p className="text-sm text-muted-foreground">Сообщений пока нет.</p>
        ) : (
          <div className="max-h-[min(65vh,32rem)] overflow-y-auto pr-1">
            <div className="flex flex-col gap-2">
              {reports.map((report) => (
                <article
                  key={report.reportId}
                  className="rounded-md border p-3 text-sm"
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="truncate font-medium">
                        {report.entryTitle}
                      </p>
                      <p className="text-xs text-muted-foreground">
                        {report.workerName} · этап {report.routeIndex + 1} ·{" "}
                        {formatDateTime(report.occurredAt)}
                      </p>
                    </div>
                    {report.readAt ? (
                      <Badge variant="muted">Прочитано</Badge>
                    ) : (
                      <Badge variant="warning">Новое</Badge>
                    )}
                  </div>
                  <p className="mt-2 whitespace-pre-wrap">{report.comment}</p>
                  {(report.missingItems ?? []).length ? (
                    <p className="mt-2 text-xs text-destructive">
                      Нет:{" "}
                      {(report.missingItems ?? [])
                        .map(
                          (item) =>
                            `${item.kind === "WORK" ? "работа" : "материал"} «${item.name}»`
                        )
                        .join(", ")}
                      {report.unitNumber ? ` · ${report.unitNumber}` : ""}
                    </p>
                  ) : null}
                  {(report.missingItems ?? []).length &&
                  !report.appliedToAll &&
                  canApplyToAll ? (
                    <Button
                      type="button"
                      size="xs"
                      variant="destructive"
                      className="mt-2"
                      disabled={applyToAll.isPending}
                      onClick={() => {
                        const operationId =
                          applyOperationIds.current.get(report.reportId) ??
                          crypto.randomUUID()
                        applyOperationIds.current.set(
                          report.reportId,
                          operationId
                        )
                        applyToAll.mutate({
                          reportId: report.reportId,
                          operationId,
                        })
                      }}
                    >
                      Применить ко всем заданиям
                    </Button>
                  ) : null}
                  {applyErrorReportId === report.reportId ? (
                    <p role="alert" className="mt-2 text-xs text-destructive">
                      Не удалось применить отсутствие ко всем заданиям.
                      Повторите попытку.
                    </p>
                  ) : null}
                  {report.appliedToAll ? (
                    <Badge className="mt-2" variant="destructive">
                      Применено ко всем заданиям
                    </Badge>
                  ) : null}
                  {report.attachments.length ? (
                    <div
                      className="mt-2 flex flex-wrap gap-2"
                      aria-label="Фотографии проблемы"
                    >
                      {report.attachments.map((attachment) => (
                        <ProtectedThumbnail
                          key={attachment.evidenceId}
                          accessToken={accessToken!}
                          warehouseId={warehouseId}
                          userId={userId}
                          attachment={attachment}
                        />
                      ))}
                    </div>
                  ) : null}
                  {!report.readAt ? (
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      <Button
                        type="button"
                        size="xs"
                        variant="ghost"
                        disabled={markRead.isPending}
                        onClick={() => markRead.mutate(report.reportId)}
                      >
                        {readErrorReportId === report.reportId
                          ? "Повторить отметку"
                          : "Отметить прочитанным"}
                      </Button>
                      {readErrorReportId === report.reportId ? (
                        <p role="alert" className="text-xs text-destructive">
                          Не удалось отметить сообщение прочитанным.
                        </p>
                      ) : null}
                    </div>
                  ) : null}
                </article>
              ))}
              {reportsQuery.hasNextPage ? (
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={reportsQuery.isFetchingNextPage}
                  onClick={() => reportsQuery.fetchNextPage()}
                >
                  {reportsQuery.isFetchingNextPage
                    ? "Загружаем…"
                    : "Загрузить ещё"}
                </Button>
              ) : null}
            </div>
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}
