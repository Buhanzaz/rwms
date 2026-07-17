import { useEffect, useMemo } from "react"
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
  getInventoryMediaOriginal,
  listInventoryMedia,
  uploadInventoryMedia,
} from "@/features/inventory/adapters/http-inventory-media-adapter"
import type {
  InventoryMediaReference,
  InventoryMediaScope,
} from "@/features/inventory/model/inventory-service"

const statusLabel = {
  UPLOADING: "Загрузка",
  PROCESSING: "Обработка",
  READY: "Готов",
  FAILED: "Ошибка",
  DELETED: "Удалён",
} as const

export function InventoryMediaEditor({
  accessToken,
  scope,
  readOnly,
  onReadyChange,
}: {
  accessToken: string | null
  scope: InventoryMediaScope
  readOnly: boolean
  onReadyChange: (references: InventoryMediaReference[]) => void
}) {
  const queryClient = useQueryClient()
  const queryKey = ["inventory", "media", scope.warehouseId, scope.ownerId]
  const query = useQuery({
    queryKey,
    queryFn: () => listInventoryMedia(accessToken, scope),
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
      (query.data?.items ?? [])
        .filter((item) => item.status === "READY")
        .map((item) => ({ mediaId: item.id, generation: item.generation })),
    [query.data?.items]
  )
  useEffect(
    () => onReadyChange(readyReferences),
    [onReadyChange, readyReferences]
  )

  const uploadMutation = useMutation({
    mutationFn: async (files: File[]) => {
      for (const [index, file] of files.entries()) {
        await uploadInventoryMedia({
          accessToken,
          scope,
          file,
          sortOrder: (query.data?.items.length ?? 0) + index,
        })
      }
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey })
      toast.success("Файлы загружены и переданы в обработку")
    },
  })

  const openOriginal = async (mediaId: string) => {
    const capability = await getInventoryMediaOriginal(
      accessToken,
      scope,
      mediaId
    )
    window.open(capability.url, "_blank", "noopener,noreferrer")
  }

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
            Сохранить осмотр можно после статуса «Готов».
          </FieldDescription>
        </Field>
      ) : null}

      {uploadMutation.error ? (
        <p role="alert" className="text-sm text-destructive">
          {uploadMutation.error instanceof Error
            ? uploadMutation.error.message
            : "Загрузка не выполнена"}
        </p>
      ) : null}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {(query.data?.items ?? []).map((item) => {
          const preview =
            item.variants.find((variant) => variant.kind === "MEDIUM") ??
            item.variants[0]
          return (
            <Card key={item.id} size="sm">
              <CardHeader>
                <CardTitle className="truncate">{item.fileName}</CardTitle>
                <CardDescription>Поколение {item.generation}</CardDescription>
              </CardHeader>
              <CardContent className="flex flex-col gap-2">
                {preview && item.kind === "IMAGE" ? (
                  <img
                    src={preview.url}
                    alt={item.fileName}
                    className="aspect-video w-full rounded-md object-cover"
                  />
                ) : null}
                <Badge
                  variant={item.status === "READY" ? "default" : "secondary"}
                >
                  {statusLabel[item.status]}
                </Badge>
              </CardContent>
              {item.status === "READY" ? (
                <CardFooter>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => void openOriginal(item.id)}
                  >
                    Открыть оригинал
                  </Button>
                </CardFooter>
              ) : null}
            </Card>
          )
        })}
      </div>
    </section>
  )
}
