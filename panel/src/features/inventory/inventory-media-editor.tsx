import { useEffect, useMemo, useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  createHttpMediaClient,
  inventoryFindingMediaOwner,
  readyMediaReference,
  type DisposableMediaObjectUrl,
  type InventoryFindingMediaOwner,
  type MediaAsset,
  type MediaVariant,
} from "@/features/media/media-service"
import type {
  InventoryMediaReference,
  InventoryMediaScope,
} from "@/features/inventory/model/inventory-service"

const mediaClient = createHttpMediaClient()

const statusLabel = {
  UPLOADING: "Загрузка",
  PROCESSING: "Обработка",
  READY: "Готов",
  FAILED: "Ошибка",
  DELETED: "Удалён",
} as const

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Операция с медиа не выполнена"
}

function preferredPreview(asset: MediaAsset): MediaVariant | null {
  return (
    asset.variants.find((variant) => variant.kind === "MEDIUM") ??
    asset.variants.find((variant) => variant.kind === "SMALL") ??
    asset.variants[0] ??
    null
  )
}

function InventoryMediaPreview({
  accessToken,
  owner,
  asset,
}: {
  accessToken: string
  owner: InventoryFindingMediaOwner
  asset: MediaAsset
}) {
  const variant = preferredPreview(asset)
  const requestKey = `${asset.id}:${asset.version}:${variant?.contentPath ?? "none"}`
  const [preview, setPreview] = useState<{
    requestKey: string
    objectUrl: DisposableMediaObjectUrl | null
    failed: boolean
  } | null>(null)

  useEffect(() => {
    if (!variant || asset.kind !== "IMAGE" || asset.status !== "READY") {
      return
    }

    let active = true
    let loaded: DisposableMediaObjectUrl | null = null
    void mediaClient
      .createVariantObjectUrl(accessToken, owner, variant)
      .then((result) => {
        if (!active) {
          result.dispose()
          return
        }
        loaded = result
        setPreview({ requestKey, objectUrl: result, failed: false })
      })
      .catch(() => {
        if (active) {
          setPreview({ requestKey, objectUrl: null, failed: true })
        }
      })

    return () => {
      active = false
      loaded?.dispose()
    }
  }, [accessToken, asset.kind, asset.status, owner, requestKey, variant])

  const current = preview?.requestKey === requestKey ? preview : null
  if (current?.objectUrl) {
    return (
      <img
        src={current.objectUrl.url}
        alt={asset.fileName}
        className="aspect-video w-full rounded-md object-cover"
      />
    )
  }

  if (current?.failed) {
    return (
      <p className="text-xs text-destructive">Превью временно недоступно</p>
    )
  }

  return null
}

export function InventoryMediaEditor({
  accessToken,
  scope,
  readOnly,
  onReadyChange,
  onPendingChange,
}: {
  accessToken: string | null
  scope: InventoryMediaScope
  readOnly: boolean
  onReadyChange: (references: InventoryMediaReference[]) => void
  onPendingChange: (pending: boolean) => void
}) {
  const queryClient = useQueryClient()
  const owner = useMemo(
    () => inventoryFindingMediaOwner(scope.ownerId, scope.warehouseId),
    [scope.ownerId, scope.warehouseId]
  )
  const queryKey = [
    "inventory-service",
    "media",
    owner.warehouseId,
    owner.ownerId,
  ] as const
  const openObjects = useRef(new Set<DisposableMediaObjectUrl>())
  const rotateKeys = useRef(new Map<string, string>())
  const query = useQuery({
    queryKey,
    queryFn: () =>
      mediaClient.listOwnerMedia(accessToken!, owner, { limit: 100 }),
    enabled: accessToken !== null,
    refetchInterval: (result) =>
      result.state.data?.items.some(
        (item) => item.status === "UPLOADING" || item.status === "PROCESSING"
      )
        ? 2_000
        : false,
  })
  const readyReferences = useMemo(
    () =>
      (query.data?.items ?? []).flatMap((asset) => {
        const reference = readyMediaReference(asset)
        return reference ? [reference] : []
      }),
    [query.data?.items]
  )

  useEffect(() => {
    if (query.data) onReadyChange(readyReferences)
  }, [onReadyChange, query.data, readyReferences])

  useEffect(
    () => () => {
      openObjects.current.forEach((objectUrl) => objectUrl.dispose())
      openObjects.current.clear()
    },
    []
  )

  const uploadMutation = useMutation({
    mutationFn: async (files: File[]) => {
      if (!accessToken) throw new Error("Для загрузки требуется авторизация")
      const offset = query.data?.items.length ?? 0
      for (const [index, file] of files.entries()) {
        await mediaClient.uploadFile(accessToken, owner, file, offset + index)
      }
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey })
      toast.success("Файлы загружены и переданы в обработку")
    },
  })
  const rotateMutation = useMutation({
    mutationFn: async (asset: MediaAsset) => {
      if (!accessToken) throw new Error("Для поворота требуется авторизация")
      const rotationDegrees = ((asset.rotationDegrees + 90) % 360) as
        0 | 90 | 180 | 270
      const signature = `${asset.id}:${asset.version}:${rotationDegrees}`
      const idempotencyKey =
        rotateKeys.current.get(signature) ?? crypto.randomUUID()
      rotateKeys.current.set(signature, idempotencyKey)
      return mediaClient.rotate(
        accessToken,
        owner,
        asset.id,
        rotationDegrees,
        asset.version,
        idempotencyKey
      )
    },
    onSuccess: async (_, asset) => {
      rotateKeys.current.delete(
        `${asset.id}:${asset.version}:${(asset.rotationDegrees + 90) % 360}`
      )
      await queryClient.invalidateQueries({ queryKey })
    },
  })
  const mediaPending =
    query.isFetching ||
    uploadMutation.isPending ||
    rotateMutation.isPending ||
    (query.data?.items.some(
      (item) => item.status === "UPLOADING" || item.status === "PROCESSING"
    ) ??
      false)

  useEffect(
    () => onPendingChange(mediaPending),
    [mediaPending, onPendingChange]
  )

  const openOriginal = async (mediaId: string) => {
    if (!accessToken) throw new Error("Для просмотра требуется авторизация")
    const objectUrl = await mediaClient.createOriginalObjectUrl(
      accessToken,
      owner,
      mediaId
    )
    openObjects.current.add(objectUrl)
    const opened = window.open(objectUrl.url, "_blank", "noopener,noreferrer")
    if (!opened) {
      objectUrl.dispose()
      openObjects.current.delete(objectUrl)
      throw new Error("Браузер заблокировал открытие оригинала")
    }
    window.setTimeout(() => {
      objectUrl.dispose()
      openObjects.current.delete(objectUrl)
    }, 60_000)
  }

  const operationError =
    uploadMutation.error ?? rotateMutation.error ?? query.error

  return (
    <section className="flex flex-col gap-3" aria-label="Медиа осмотра">
      {!readOnly ? (
        <Field data-disabled={uploadMutation.isPending}>
          <FieldLabel htmlFor="inventory-media-files">Фото и видео</FieldLabel>
          <Input
            id="inventory-media-files"
            type="file"
            accept="image/*,video/*"
            multiple
            disabled={uploadMutation.isPending}
            onChange={(event) => {
              const files = Array.from(event.target.files ?? [])
              if (files.length > 0) uploadMutation.mutate(files)
              event.currentTarget.value = ""
            }}
          />
          <FieldDescription>
            Осмотр получит только файлы со статусом «Готов».
          </FieldDescription>
        </Field>
      ) : null}

      {operationError ? (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(operationError)}
        </p>
      ) : null}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {(query.data?.items ?? []).map((asset) => (
          <Card key={asset.id} size="sm">
            <CardHeader>
              <CardTitle className="truncate">{asset.fileName}</CardTitle>
              <CardDescription>
                Поколение {asset.generation} · версия {asset.version}
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-2">
              {accessToken ? (
                <InventoryMediaPreview
                  accessToken={accessToken}
                  owner={owner}
                  asset={asset}
                />
              ) : null}
              <Badge
                variant={asset.status === "READY" ? "default" : "secondary"}
              >
                {statusLabel[asset.status]}
              </Badge>
            </CardContent>
            {asset.status === "READY" ? (
              <CardFooter className="flex flex-wrap gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() =>
                    void openOriginal(asset.id).catch((error) =>
                      toast.error(errorMessage(error))
                    )
                  }
                >
                  Открыть оригинал
                </Button>
                {!readOnly && asset.kind === "IMAGE" ? (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={rotateMutation.isPending}
                    onClick={() => rotateMutation.mutate(asset)}
                  >
                    Повернуть 90°
                  </Button>
                ) : null}
              </CardFooter>
            ) : null}
          </Card>
        ))}
      </div>
    </section>
  )
}
