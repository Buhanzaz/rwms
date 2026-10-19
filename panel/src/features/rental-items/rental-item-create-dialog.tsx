import {
  useEffect,
  useId,
  useRef,
  useState,
  type FormEvent,
  type ReactElement,
} from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { CheckIcon } from "@hugeicons/core-free-icons"

import {
  abandonRentalItemCreationIntent,
  AssetRentalItemConflictError,
  completeRentalItemCreationIntent,
  createAssetRentalItemWithPhotoIntent,
  createIdempotencyKey,
  getAssetRentalItem,
  getRentalItemCreationOptions,
  listPendingRentalItemCreationIntents,
  rentalItemCreationOptionsQueryKey,
  rentalItemCreationIntentsQueryKey,
  type RentalItemCreationIntent,
  type RentalItemCreationPhotoManifestInput,
} from "@/features/rental-items/api/asset-rental-items-api"
import {
  cabinMediaOwner,
  createHttpMediaClient,
  type MediaAsset,
} from "@/features/media/media-service"
import { retryOwnerProofOperation } from "@/features/media/owner-proof-retry"
import {
  creationPhotoCommandKeys,
  matchRentalItemCreationManifest,
  prepareRentalItemCreationPhotos,
  validateCreatedRentalItemIntent,
  waitForRentalItemCreationPhotosReady,
  type PreparedRentalItemCreationPhotos,
} from "@/features/rental-items/rental-item-creation-intent-support"
import {
  RentalItemCreationPhotoUploader,
  type StagedRentalItemPhoto,
} from "@/features/rental-items/rental-item-creation-photo-uploader"
import { RentalItemCompositionFields } from "@/features/rental-items/rental-item-composition-fields"
import {
  compositionCategoryOptions,
  emptyRentalItemComposition,
  isRentalItemCompositionComplete,
  type RentalItemCompositionCategoryMode,
  type RentalItemCompositionFormValue,
} from "@/features/rental-items/rental-item-composition"
import type { RentalItemDto } from "@/features/rental-items/model/rental-item"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import {
  Field,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { useAuth } from "@/features/auth/use-auth"
import { hasWarehouseAccess } from "@/features/auth/warehouse-access"

type RentalItemCreateDialogProps = {
  open: boolean
  warehouseId: string
  onOpenChange: (open: boolean) => void
}

/**
 * The UUID-backed passport fields accepted by warehouse and inventory creation
 * commands. Display labels are resolved from the asset-service catalog.
 */
export type RentalItemCreationCommand = {
  idempotencyKey: string
  number: string
  rentalTypeId: string
  dimensionId: string
  finishingId: string
  category: string
  characteristicIds: string[]
  linoleum: boolean
}

/** The real asset identity needed to upload a cabin photograph. */
export type CreatedRentalItemAsset = {
  id: string
  warehouseId: string
  number: string
}

export type RentalItemCreationResult<T> = {
  createdItem: CreatedRentalItemAsset
  value: T
}

/** Result of atomically creating a cabin and its server-owned photo intent. */
export type RentalItemPhotoIntentCreationResult<T> =
  RentalItemCreationResult<T> & {
    intent: RentalItemCreationIntent
  }

/** Asset-owned operations injected into the shared creation form. */
export type RentalItemPhotoIntentWorkflow<T> = {
  pendingQueryKey: readonly unknown[]
  listPending: () => Promise<readonly RentalItemCreationIntent[]>
  create: (
    command: RentalItemCreationCommand,
    manifest: readonly RentalItemCreationPhotoManifestInput[]
  ) => Promise<RentalItemPhotoIntentCreationResult<T>>
  load: (
    intent: RentalItemCreationIntent
  ) => Promise<RentalItemCreationResult<T>>
  complete: (
    intent: RentalItemCreationIntent,
    idempotencyKey: string
  ) => Promise<RentalItemCreationIntent>
  abandon: (
    intent: RentalItemCreationIntent,
    idempotencyKey: string
  ) => Promise<RentalItemCreationIntent>
}

/** Shared presentation callbacks and composition fields for both create modes. */
type RentalItemCreationDialogCommonProps<T> = {
  open: boolean
  warehouseId: string
  onOpenChange: (open: boolean) => void
  /** Keep the warehouse permission gate in the warehouse wrapper only. */
  canCreate?: boolean
  title: string
  description?: string
  initialNumber?: string
  numberReadOnly?: boolean
  categoryMode?: Extract<RentalItemCompositionCategoryMode, "NEW" | "USED">
  submitLabel?: string
  /** Runs as soon as the cabin itself exists, before staged photos upload. */
  onAssetCreated?: (value: T, createdItem: CreatedRentalItemAsset) => void
  /** Runs only after all staged photos have completed (or there were none). */
  onCompleted?: (value: T, createdItem: CreatedRentalItemAsset) => void
  errorMessage?: (error: unknown) => string | null
}

/** Warehouse create mode whose mandatory photos are owned by a durable intent. */
type RentalItemPhotoIntentDialogProps<T> =
  RentalItemCreationDialogCommonProps<T> & {
    /** Photo creation is the default and has no browser-owned fallback. */
    photosEnabled?: true
    createRentalItem?: never
    photoIntentWorkflow: RentalItemPhotoIntentWorkflow<T>
  }

/** Inventory create mode whose later inspection owns photo collection. */
type RentalItemLegacyCreationDialogProps<T> =
  RentalItemCreationDialogCommonProps<T> & {
    /** Inventory attaches photos later to the inspection finding. */
    photosEnabled: false
    createRentalItem: (
      command: RentalItemCreationCommand
    ) => Promise<RentalItemCreationResult<T>>
    photoIntentWorkflow?: never
  }

/** Supported mutually exclusive creation modes for the shared dialog. */
export type RentalItemCreationDialogProps<T> =
  RentalItemPhotoIntentDialogProps<T> | RentalItemLegacyCreationDialogProps<T>

type RentalItemCreateFormState = {
  number: string
  composition: RentalItemCompositionFormValue
  photos: StagedRentalItemPhoto[]
}

const formFooterClassName = "w-full md:w-[16.25rem] md:self-end"
const RENTAL_ITEM_NUMBER_PATTERN = /^[\p{L}\p{N}][\p{L}\p{N} _-]{0,127}$/u

function disposeStagedRentalItemPhotos(
  photos: readonly StagedRentalItemPhoto[]
) {
  photos.forEach((photo) => URL.revokeObjectURL(photo.previewUrl))
}

function hasStagedRentalItemTitlePhoto(
  photos: readonly StagedRentalItemPhoto[]
) {
  return photos.length > 0 && photos.some((photo) => photo.title)
}

function rentalItemNumberError(number: string) {
  const normalizedNumber = number.trim()

  if (normalizedNumber.length === 0) return "Укажите номер бытовки."

  if (!RENTAL_ITEM_NUMBER_PATTERN.test(normalizedNumber)) {
    return "Номер бытовки должен содержать от 1 до 128 символов, начинаться с буквы или цифры и включать только буквы, цифры, пробелы, дефис (-) или символ подчёркивания (_)."
  }

  return null
}

function createEmptyForm({
  number = "",
  composition = emptyRentalItemComposition(),
}: {
  number?: string
  composition?: RentalItemCompositionFormValue
} = {}): RentalItemCreateFormState {
  return {
    number,
    composition,
    photos: [],
  }
}

function initialCompositionCategory(
  options: Parameters<typeof compositionCategoryOptions>[0] | undefined,
  categoryMode: Extract<RentalItemCompositionCategoryMode, "NEW" | "USED">
) {
  if (!options) return ""
  return compositionCategoryOptions(options, categoryMode, "")[0] ?? ""
}

const rentalItemCreationMediaClient = createHttpMediaClient()

function hasRequiredFormFields(
  form: RentalItemCreateFormState,
  photosEnabled: boolean
) {
  return (
    rentalItemNumberError(form.number) === null &&
    isRentalItemCompositionComplete(form.composition) &&
    (!photosEnabled || hasStagedRentalItemTitlePhoto(form.photos))
  )
}

export function RentalItemCreationDialog<T>(
  props: RentalItemPhotoIntentDialogProps<T>
): ReactElement
export function RentalItemCreationDialog<T>(
  props: RentalItemLegacyCreationDialogProps<T>
): ReactElement
export function RentalItemCreationDialog<T>({
  open,
  warehouseId,
  onOpenChange,
  canCreate = true,
  title,
  description,
  initialNumber,
  numberReadOnly = false,
  categoryMode = "NEW",
  photosEnabled = true,
  submitLabel = "Создать бытовку",
  createRentalItem,
  photoIntentWorkflow,
  onAssetCreated,
  onCompleted,
  errorMessage,
}: RentalItemCreationDialogProps<T>) {
  const queryClient = useQueryClient()
  const { accessToken } = useAuth()
  const numberInputId = useId()
  const [form, setForm] = useState<RentalItemCreateFormState>(() =>
    createEmptyForm({ number: initialNumber })
  )
  const [submitted, setSubmitted] = useState(false)
  const [createdResult, setCreatedResult] =
    useState<RentalItemCreationResult<T> | null>(null)
  const [activeIntent, setActiveIntent] =
    useState<RentalItemCreationIntent | null>(null)
  const [abandonTarget, setAbandonTarget] =
    useState<RentalItemCreationIntent | null>(null)
  const [completionNotice, setCompletionNotice] = useState<string | null>(null)
  const [photoUploadPending, setPhotoUploadPending] = useState(false)
  const [photoUploadError, setPhotoUploadError] = useState<string | null>(null)
  const completionKeys = useRef(new Map<string, string>())
  const abandonmentKeys = useRef(new Map<string, string>())
  const creationAttemptKeys = useRef(new Map<string, string>())
  const creationOptionsQuery = useQuery({
    queryKey: rentalItemCreationOptionsQueryKey(warehouseId),
    queryFn: () => getRentalItemCreationOptions(accessToken, warehouseId),
    enabled: Boolean(open && canCreate && accessToken && warehouseId),
  })
  const pendingIntentsQuery = useQuery({
    queryKey: photoIntentWorkflow?.pendingQueryKey ?? [
      "rental-item-creation-intents-disabled",
      warehouseId,
    ],
    queryFn: () => photoIntentWorkflow?.listPending() ?? Promise.resolve([]),
    enabled: Boolean(
      open && canCreate && photosEnabled && accessToken && photoIntentWorkflow
    ),
  })
  const creationOptions = creationOptionsQuery.data
  const numberError = submitted ? rentalItemNumberError(form.number) : null

  useEffect(() => {
    if (!open || !creationOptions) return

    const category = initialCompositionCategory(creationOptions, categoryMode)
    let active = true
    void Promise.resolve().then(() => {
      if (!active) return
      setForm((current) => {
        const allowedCategories = compositionCategoryOptions(
          creationOptions,
          categoryMode,
          current.composition.category
        )
        const nextCategory =
          categoryMode === "NEW"
            ? category
            : allowedCategories.includes(current.composition.category)
              ? current.composition.category
              : category
        if (nextCategory === current.composition.category) return current
        return {
          ...current,
          composition: { ...current.composition, category: nextCategory },
        }
      })
    })
    return () => {
      active = false
    }
  }, [categoryMode, creationOptions, open])

  function resetDialogState(photos = form.photos) {
    disposeStagedRentalItemPhotos(photos)
    setForm(
      createEmptyForm({
        number: initialNumber,
        composition: {
          ...emptyRentalItemComposition(),
          category: initialCompositionCategory(creationOptions, categoryMode),
        },
      })
    )
    setSubmitted(false)
    setCreatedResult(null)
    setActiveIntent(null)
    setAbandonTarget(null)
    setCompletionNotice(null)
    setPhotoUploadError(null)
    setPhotoUploadPending(false)
    completionKeys.current.clear()
    abandonmentKeys.current.clear()
    creationAttemptKeys.current.clear()
  }

  function finishCreation(
    result: RentalItemCreationResult<T>,
    photos: StagedRentalItemPhoto[]
  ) {
    try {
      onCompleted?.(result.value, result.createdItem)
    } catch (error) {
      setPhotoUploadError(
        `Бытовка ${result.createdItem.number} создана, но не удалось открыть результат: ${
          error instanceof Error ? error.message : "неизвестная ошибка"
        }`
      )
      return
    }
    resetDialogState(photos)
    onOpenChange(false)
  }

  async function uploadCreatedPhotos(
    result: RentalItemCreationResult<T>,
    intent: RentalItemCreationIntent,
    photos: StagedRentalItemPhoto[],
    preparedPhotos?: PreparedRentalItemCreationPhotos
  ) {
    const item = result.createdItem
    if (!accessToken || !photoIntentWorkflow) {
      setPhotoUploadError(
        "Создание бытовки не завершено: недоступна защищённая загрузка фотографий. Откройте незавершённое создание позже."
      )
      return
    }

    setPhotoUploadPending(true)
    setPhotoUploadError(null)
    const owner = cabinMediaOwner(item.id, item.warehouseId)
    try {
      const prepared =
        preparedPhotos ??
        (await prepareRentalItemCreationPhotos(
          rentalItemCreationMediaClient,
          photos
        ))
      const manifestMatch = matchRentalItemCreationManifest(prepared, intent)
      if (!manifestMatch.matches) throw new Error(manifestMatch.message)

      const uploadedAssets: MediaAsset[] = []
      for (const [index, photo] of prepared.photos.entries()) {
        const manifestEntry = intent.photoManifest[index]
        if (!manifestEntry) {
          throw new Error(
            "Сохранённый состав фотографий неполон. Создание осталось незавершённым."
          )
        }
        const upload = await retryOwnerProofOperation(() =>
          rentalItemCreationMediaClient.uploadFile(
            accessToken,
            owner,
            photo.file,
            index,
            intent.mediaFolderId,
            creationPhotoCommandKeys(manifestEntry)
          )
        )
        uploadedAssets.push(upload.asset)
      }
      await waitForRentalItemCreationPhotosReady({
        mediaClient: rentalItemCreationMediaClient,
        accessToken,
        owner,
        folderId: intent.mediaFolderId,
        uploadedAssets,
      })
      const completionKey =
        completionKeys.current.get(intent.id) ?? createIdempotencyKey()
      completionKeys.current.set(intent.id, completionKey)
      const completedIntent = await photoIntentWorkflow.complete(
        intent,
        completionKey
      )
      if (completedIntent.state !== "COMPLETED") {
        throw new Error(
          "Сервис имущества не подтвердил завершение создания бытовки."
        )
      }
      await queryClient.invalidateQueries({ queryKey: ["rental-item-media"] })
      await queryClient.invalidateQueries({
        queryKey: ["rental-item-media-covers"],
      })
      await queryClient.invalidateQueries({
        queryKey: photoIntentWorkflow.pendingQueryKey,
      })
      finishCreation(result, photos)
    } catch (error) {
      setPhotoUploadError(
        `Создание бытовки ${item.number} не завершено: ${
          error instanceof Error ? error.message : "сервис фото недоступен"
        } Бытовка пока недоступна для аренды.`
      )
    } finally {
      setPhotoUploadPending(false)
    }
  }

  const createMutation = useMutation({
    mutationFn: async (
      input: RentalItemCreationCommand & { photos: StagedRentalItemPhoto[] }
    ) => {
      if (!photosEnabled) {
        if (!createRentalItem) {
          throw new Error("Создание бытовки без фотографий не настроено.")
        }
        return {
          result: await createRentalItem(input),
          intent: null,
          preparedPhotos: null,
        }
      }
      if (!photoIntentWorkflow) {
        throw new Error(
          "Создание бытовки с обязательными фотографиями не настроено."
        )
      }
      const preparedPhotos = await prepareRentalItemCreationPhotos(
        rentalItemCreationMediaClient,
        input.photos
      )
      const creationFingerprint = JSON.stringify({
        number: input.number,
        rentalTypeId: input.rentalTypeId,
        dimensionId: input.dimensionId,
        finishingId: input.finishingId,
        category: input.category,
        characteristicIds: input.characteristicIds,
        linoleum: input.linoleum,
        photoManifest: preparedPhotos.manifest,
      })
      const idempotencyKey =
        creationAttemptKeys.current.get(creationFingerprint) ??
        input.idempotencyKey
      creationAttemptKeys.current.set(creationFingerprint, idempotencyKey)
      const created = await photoIntentWorkflow.create(
        { ...input, idempotencyKey },
        preparedPhotos.manifest
      )
      validateCreatedRentalItemIntent(
        created.intent,
        created.createdItem,
        preparedPhotos
      )
      return {
        result: created,
        intent: created.intent,
        preparedPhotos,
      }
    },
    onSuccess: ({ result, intent, preparedPhotos }, input) => {
      setCreatedResult(result)
      try {
        onAssetCreated?.(result.value, result.createdItem)
      } catch (error) {
        setPhotoUploadError(
          `Бытовка ${result.createdItem.number} создана, но не удалось обновить экран: ${
            error instanceof Error ? error.message : "неизвестная ошибка"
          }`
        )
      }
      if (!intent || !preparedPhotos) {
        finishCreation(result, [])
        return
      }
      setActiveIntent(intent)
      void queryClient.invalidateQueries({
        queryKey: photoIntentWorkflow?.pendingQueryKey,
      })
      void uploadCreatedPhotos(result, intent, input.photos, preparedPhotos)
    },
    onError: () => {
      if (photosEnabled && photoIntentWorkflow) {
        void queryClient.invalidateQueries({
          queryKey: photoIntentWorkflow.pendingQueryKey,
        })
      }
    },
  })

  const resumeMutation = useMutation({
    mutationFn: async (intent: RentalItemCreationIntent) => {
      if (!photoIntentWorkflow) {
        throw new Error("Продолжение создания бытовки не настроено.")
      }
      return { intent, result: await photoIntentWorkflow.load(intent) }
    },
    onSuccess: ({ intent, result }) => {
      disposeStagedRentalItemPhotos(form.photos)
      setForm((current) => ({ ...current, photos: [] }))
      setActiveIntent(intent)
      setCreatedResult(result)
      setCompletionNotice(null)
      setPhotoUploadError(null)
      onAssetCreated?.(result.value, result.createdItem)
    },
  })

  const abandonMutation = useMutation({
    mutationFn: async (intent: RentalItemCreationIntent) => {
      if (!photoIntentWorkflow) {
        throw new Error("Прекращение создания бытовки не настроено.")
      }
      const abandonmentKey =
        abandonmentKeys.current.get(intent.id) ?? createIdempotencyKey()
      abandonmentKeys.current.set(intent.id, abandonmentKey)
      const abandoned = await photoIntentWorkflow.abandon(
        intent,
        abandonmentKey
      )
      return {
        abandoned,
        result: await photoIntentWorkflow.load(abandoned),
      }
    },
    onSuccess: ({ abandoned, result }) => {
      onAssetCreated?.(result.value, result.createdItem)
      if (activeIntent?.id === abandoned.id) {
        disposeStagedRentalItemPhotos(form.photos)
        setForm((current) => ({ ...current, photos: [] }))
        setActiveIntent(null)
        setCreatedResult(null)
      }
      setCompletionNotice(
        `Создание бытовки ${result.createdItem.number} прекращено. Бытовка и загруженные фото сохранены, статус бытовки — «На складе».`
      )
      setPhotoUploadError(null)
      void queryClient.invalidateQueries({
        queryKey: photoIntentWorkflow?.pendingQueryKey,
      })
    },
    onError: (error) => {
      setPhotoUploadError(
        error instanceof Error
          ? error.message
          : "Не удалось прекратить незавершённое создание бытовки."
      )
    },
  })

  const formControlsDisabled =
    createMutation.isPending ||
    photoUploadPending ||
    resumeMutation.isPending ||
    abandonMutation.isPending

  function handleDialogOpenChange(nextOpen: boolean) {
    if (nextOpen && !canCreate) return
    if (!nextOpen && formControlsDisabled) return

    if (!nextOpen) {
      resetDialogState()
    }

    onOpenChange(nextOpen)
  }

  function submitForm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitted(true)

    if (
      !hasRequiredFormFields(form, photosEnabled) ||
      !canCreate ||
      activeIntent !== null ||
      createMutation.isPending
    ) {
      return
    }

    createMutation.mutate({
      idempotencyKey: createIdempotencyKey(),
      number: form.number.trim(),
      rentalTypeId: form.composition.rentalTypeId,
      dimensionId: form.composition.dimensionId,
      finishingId: form.composition.finishingId,
      category: form.composition.category,
      characteristicIds: form.composition.characteristicIds,
      linoleum: form.composition.linoleum === "yes",
      photos: photosEnabled ? form.photos : [],
    })
  }

  const optionsError = creationOptionsQuery.error
    ? creationOptionsQuery.error instanceof Error
      ? creationOptionsQuery.error.message
      : "Не удалось загрузить настройки бытовок."
    : null
  const pendingIntentsError = pendingIntentsQuery.error
    ? pendingIntentsQuery.error instanceof Error
      ? pendingIntentsQuery.error.message
      : "Не удалось загрузить незавершённые создания бытовок."
    : null

  function retryActiveIntent() {
    if (activeIntent && createdResult) {
      void uploadCreatedPhotos(createdResult, activeIntent, form.photos)
    }
  }

  return (
    <>
      <Dialog open={open && canCreate} onOpenChange={handleDialogOpenChange}>
        <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            {description ? (
              <DialogDescription>{description}</DialogDescription>
            ) : null}
          </DialogHeader>

          {completionNotice ? (
            <Card size="sm">
              <CardHeader>
                <CardTitle>Незавершённое создание обработано</CardTitle>
                <CardDescription>{completionNotice}</CardDescription>
              </CardHeader>
            </Card>
          ) : null}

          {photosEnabled && photoIntentWorkflow && !activeIntent ? (
            <div className="flex flex-col gap-3">
              {pendingIntentsQuery.isPending ? (
                <p className="text-sm text-muted-foreground">
                  Проверяем незавершённые создания…
                </p>
              ) : null}
              {pendingIntentsError ? (
                <FieldError role="alert">{pendingIntentsError}</FieldError>
              ) : null}
              {(pendingIntentsQuery.data ?? []).length > 0 ? (
                <Card size="sm">
                  <CardHeader>
                    <CardTitle>Незавершённые создания</CardTitle>
                    <CardDescription>
                      Бытовки ещё удерживаются сервисом имущества и недоступны
                      для аренды. Выберите исходные фото для продолжения или
                      явно прекратите создание.
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="flex flex-col gap-3">
                    {(pendingIntentsQuery.data ?? []).map((intent) => (
                      <Card key={intent.id} size="sm">
                        <CardHeader>
                          <CardTitle>
                            Бытовка {intent.rentalItemId.slice(0, 8)}…
                          </CardTitle>
                          <CardDescription>
                            Требуется фото: {intent.expectedPhotoCount}. Создано{" "}
                            {new Date(intent.createdAt).toLocaleString("ru-RU")}
                            .
                          </CardDescription>
                        </CardHeader>
                        <CardFooter className="flex flex-wrap justify-end gap-2">
                          <Button
                            type="button"
                            variant="outline"
                            disabled={formControlsDisabled}
                            onClick={() => setAbandonTarget(intent)}
                          >
                            Прекратить
                          </Button>
                          <Button
                            type="button"
                            disabled={formControlsDisabled}
                            onClick={() => resumeMutation.mutate(intent)}
                          >
                            Продолжить
                          </Button>
                        </CardFooter>
                      </Card>
                    ))}
                  </CardContent>
                </Card>
              ) : null}
            </div>
          ) : null}

          {activeIntent && createdResult ? (
            <Card size="sm">
              <CardHeader>
                <CardTitle>
                  Продолжение создания {createdResult.createdItem.number}
                </CardTitle>
                <CardDescription>
                  Повторно выберите ровно {activeIntent.expectedPhotoCount} фото
                  из исходного набора. Титульное фото должно остаться первым в
                  сохранённом составе.
                </CardDescription>
              </CardHeader>
              <CardContent className="flex flex-col gap-4">
                <div
                  aria-busy={formControlsDisabled || undefined}
                  aria-disabled={formControlsDisabled || undefined}
                  inert={formControlsDisabled || undefined}
                >
                  <RentalItemCreationPhotoUploader
                    photos={form.photos}
                    disabled={formControlsDisabled}
                    titlePhotoMissing={
                      submitted && !hasStagedRentalItemTitlePhoto(form.photos)
                    }
                    onChange={(photos) =>
                      setForm((current) => ({ ...current, photos }))
                    }
                  />
                </div>
                {photoUploadError ? (
                  <FieldError role="alert">{photoUploadError}</FieldError>
                ) : null}
              </CardContent>
              <CardFooter className="flex flex-wrap justify-end gap-2">
                <Button
                  type="button"
                  variant="outline"
                  disabled={formControlsDisabled}
                  onClick={() => handleDialogOpenChange(false)}
                >
                  Отложить
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  disabled={formControlsDisabled}
                  onClick={() => setAbandonTarget(activeIntent)}
                >
                  Прекратить
                </Button>
                <Button
                  type="button"
                  disabled={formControlsDisabled}
                  onClick={retryActiveIntent}
                >
                  <HugeiconsIcon icon={CheckIcon} data-icon="inline-start" />
                  {photoUploadPending
                    ? "Загрузка и проверка..."
                    : "Загрузить и завершить"}
                </Button>
              </CardFooter>
            </Card>
          ) : !creationOptions ? (
            <div className="flex flex-col gap-4">
              {optionsError ? (
                <FieldError role="alert">{optionsError}</FieldError>
              ) : (
                <p className="text-sm text-muted-foreground">
                  Загружаем настройки бытовок…
                </p>
              )}
              <DialogFooter>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => handleDialogOpenChange(false)}
                >
                  Отмена
                </Button>
                {optionsError ? (
                  <Button
                    type="button"
                    onClick={() => void creationOptionsQuery.refetch()}
                  >
                    Повторить
                  </Button>
                ) : null}
              </DialogFooter>
            </div>
          ) : (
            <form className="flex flex-col gap-5" onSubmit={submitForm}>
              <div
                aria-busy={formControlsDisabled || undefined}
                aria-disabled={formControlsDisabled || undefined}
                inert={formControlsDisabled || undefined}
                className="flex min-w-0 flex-col gap-5"
              >
                <FieldGroup>
                  <Field data-invalid={numberError !== null}>
                    <FieldLabel htmlFor={numberInputId}>
                      Номер бытовки
                    </FieldLabel>
                    <Input
                      id={numberInputId}
                      value={form.number}
                      maxLength={128}
                      readOnly={numberReadOnly}
                      aria-invalid={numberError !== null}
                      onChange={(event) =>
                        setForm((current) => ({
                          ...current,
                          number: event.target.value,
                        }))
                      }
                      placeholder="Например, БЫТ-121"
                    />
                    {numberError ? (
                      <FieldError>{numberError}</FieldError>
                    ) : null}
                  </Field>
                </FieldGroup>

                <RentalItemCompositionFields
                  options={creationOptions}
                  value={form.composition}
                  categoryMode={categoryMode}
                  submitted={submitted}
                  disabled={formControlsDisabled}
                  onChange={(composition) =>
                    setForm((current) => ({ ...current, composition }))
                  }
                />

                {photosEnabled ? (
                  <RentalItemCreationPhotoUploader
                    photos={form.photos}
                    disabled={formControlsDisabled}
                    titlePhotoMissing={
                      submitted && !hasStagedRentalItemTitlePhoto(form.photos)
                    }
                    onChange={(photos) =>
                      setForm((current) => ({ ...current, photos }))
                    }
                  />
                ) : null}
              </div>

              {createMutation.isError ? (
                <FieldError>
                  {errorMessage?.(createMutation.error) ??
                    (createMutation.error instanceof Error
                      ? createMutation.error.message
                      : "Не удалось создать бытовку. Проверьте данные и повторите.")}
                </FieldError>
              ) : null}

              {photoUploadError ? (
                <FieldError role="alert">{photoUploadError}</FieldError>
              ) : null}

              <DialogFooter className={formFooterClassName}>
                <Button
                  type="button"
                  variant="outline"
                  className="flex-1"
                  disabled={formControlsDisabled}
                  onClick={() => handleDialogOpenChange(false)}
                >
                  Отмена
                </Button>
                <Button
                  type="submit"
                  className="flex-[1.65]"
                  disabled={!canCreate || formControlsDisabled}
                >
                  <HugeiconsIcon icon={CheckIcon} data-icon="inline-start" />
                  {createMutation.isPending ? "Создание..." : submitLabel}
                </Button>
              </DialogFooter>
            </form>
          )}
        </DialogContent>
      </Dialog>

      <AlertDialog
        open={abandonTarget !== null}
        onOpenChange={(nextOpen) => {
          if (!nextOpen && !abandonMutation.isPending) setAbandonTarget(null)
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Прекратить создание бытовки?</AlertDialogTitle>
            <AlertDialogDescription>
              Бытовка и уже загруженные фото не удалятся. Бытовка перейдёт в
              неарендный статус «На складе», а незавершённое создание закроется.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={abandonMutation.isPending}>
              Отмена
            </AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              disabled={abandonMutation.isPending}
              onClick={() => {
                if (abandonTarget) abandonMutation.mutate(abandonTarget)
              }}
            >
              {abandonMutation.isPending ? "Прекращаем..." : "Прекратить"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}

/** Warehouse entry point with its own EDIT permission gate. */
export function RentalItemCreateDialog({
  open,
  warehouseId,
  onOpenChange,
}: RentalItemCreateDialogProps) {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const canEditRentalItems = hasWarehouseAccess(
    currentUser,
    warehouseId,
    "EDIT"
  )

  function refreshRentalItemQueries(item: RentalItemDto) {
    queryClient.setQueryData(["rental-item", item.id], item)
    void queryClient.invalidateQueries({ queryKey: ["rental-items"] })
    void queryClient.invalidateQueries({
      queryKey: ["rental-items-table-schema", warehouseId],
    })
    void queryClient.invalidateQueries({
      queryKey: ["rental-item-filter-options", warehouseId],
    })
  }

  function creationResult(
    item: RentalItemDto
  ): RentalItemCreationResult<RentalItemDto> {
    return {
      createdItem: {
        id: item.id,
        warehouseId: item.warehouseId,
        number: item.number,
      },
      value: item,
    }
  }

  return (
    <RentalItemCreationDialog<RentalItemDto>
      open={open}
      warehouseId={warehouseId}
      onOpenChange={onOpenChange}
      canCreate={canEditRentalItems}
      title="Создание новой бытовки"
      photoIntentWorkflow={{
        pendingQueryKey: rentalItemCreationIntentsQueryKey(warehouseId),
        listPending: async () =>
          (
            await listPendingRentalItemCreationIntents({
              accessToken,
              warehouseId,
              page: 0,
              size: 50,
            })
          ).content,
        create: async (command, photoManifest) => {
          const created = await createAssetRentalItemWithPhotoIntent({
            accessToken,
            idempotencyKey: command.idempotencyKey,
            input: {
              warehouseId,
              number: command.number,
              rentalTypeId: command.rentalTypeId,
              dimensionId: command.dimensionId,
              finishingId: command.finishingId,
              category: command.category,
              characteristicIds: command.characteristicIds,
              linoleum: command.linoleum,
            },
            photoManifest,
          })
          return {
            ...creationResult(created.rentalItem),
            intent: created.intent,
          }
        },
        load: async (intent) =>
          creationResult(
            await getAssetRentalItem(accessToken, intent.rentalItemId)
          ),
        complete: (intent, idempotencyKey) =>
          completeRentalItemCreationIntent({
            accessToken,
            intentId: intent.id,
            expectedVersion: intent.version,
            idempotencyKey,
          }),
        abandon: (intent, idempotencyKey) =>
          abandonRentalItemCreationIntent({
            accessToken,
            intentId: intent.id,
            expectedVersion: intent.version,
            idempotencyKey,
          }),
      }}
      onAssetCreated={(item) => refreshRentalItemQueries(item)}
      errorMessage={(error) =>
        error instanceof AssetRentalItemConflictError
          ? "Бытовка была изменена другим пользователем. Обновите реестр и повторите действие."
          : null
      }
    />
  )
}
