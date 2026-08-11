import { useEffect, useId, useRef, useState, type FormEvent } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import { CheckIcon } from "@hugeicons/core-free-icons"

import {
  AssetRentalItemConflictError,
  createAssetRentalItem,
  createIdempotencyKey,
  getRentalItemCreationOptions,
  rentalItemCreationOptionsQueryKey,
} from "@/features/rental-items/api/asset-rental-items-api"
import {
  cabinMediaOwner,
  createHttpMediaClient,
} from "@/features/media/media-service"
import { retryOwnerProofOperation } from "@/features/media/owner-proof-retry"
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
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
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

export type RentalItemCreationDialogProps<T> = {
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
  /** Defaults to true. Inventory attaches photos later to the inspection finding. */
  photosEnabled?: boolean
  submitLabel?: string
  createRentalItem: (
    command: RentalItemCreationCommand
  ) => Promise<RentalItemCreationResult<T>>
  /** Runs as soon as the cabin itself exists, before staged photos upload. */
  onAssetCreated?: (value: T, createdItem: CreatedRentalItemAsset) => void
  /** Runs only after all staged photos have completed (or there were none). */
  onCompleted?: (value: T, createdItem: CreatedRentalItemAsset) => void
  errorMessage?: (error: unknown) => string | null
}

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
  return photos.length === 0 || photos.some((photo) => photo.title)
}

function orderStagedRentalItemPhotosForUpload(
  photos: readonly StagedRentalItemPhoto[]
) {
  const titlePhoto = photos.find((photo) => photo.title)
  if (!titlePhoto) return [...photos]

  return [titlePhoto, ...photos.filter((photo) => photo.id !== titlePhoto.id)]
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
  const [createdItem, setCreatedItem] = useState<CreatedRentalItemAsset | null>(
    null
  )
  const [createdResult, setCreatedResult] =
    useState<RentalItemCreationResult<T> | null>(null)
  const [photoUploadPending, setPhotoUploadPending] = useState(false)
  const [photoUploadError, setPhotoUploadError] = useState<string | null>(null)
  const photoFolderId = useRef(crypto.randomUUID())
  const creationOptionsQuery = useQuery({
    queryKey: rentalItemCreationOptionsQueryKey(warehouseId),
    queryFn: () => getRentalItemCreationOptions(accessToken, warehouseId),
    enabled: Boolean(open && canCreate && accessToken && warehouseId),
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
    setCreatedItem(null)
    setCreatedResult(null)
    setPhotoUploadError(null)
    setPhotoUploadPending(false)
    photoFolderId.current = crypto.randomUUID()
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
    photos: StagedRentalItemPhoto[]
  ) {
    const item = result.createdItem
    if (!accessToken) {
      setPhotoUploadError(
        "Бытовка создана, но для загрузки фото не получен токен доступа."
      )
      return
    }

    setPhotoUploadPending(true)
    setPhotoUploadError(null)
    const owner = cabinMediaOwner(item.id, item.warehouseId)
    try {
      const orderedPhotos = orderStagedRentalItemPhotosForUpload(photos)
      for (const [index, photo] of orderedPhotos.entries()) {
        await retryOwnerProofOperation(() =>
          rentalItemCreationMediaClient.uploadFile(
            accessToken,
            owner,
            photo.file,
            index,
            photoFolderId.current,
            photo.commandKeys
          )
        )
      }
      await queryClient.invalidateQueries({ queryKey: ["rental-item-media"] })
      await queryClient.invalidateQueries({
        queryKey: ["rental-item-media-covers"],
      })
      finishCreation(result, photos)
    } catch (error) {
      setPhotoUploadError(
        `Бытовка ${item.number} создана, но фото не загружены: ${
          error instanceof Error ? error.message : "сервис фото недоступен"
        }`
      )
    } finally {
      setPhotoUploadPending(false)
    }
  }

  const createMutation = useMutation({
    mutationFn: (
      input: RentalItemCreationCommand & { photos: StagedRentalItemPhoto[] }
    ) => createRentalItem(input),
    onSuccess: (result, input) => {
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
      if (!photosEnabled || input.photos.length === 0) {
        finishCreation(result, [])
        return
      }
      setCreatedItem(result.createdItem)
      void uploadCreatedPhotos(result, input.photos)
    },
  })

  const formControlsDisabled =
    createMutation.isPending || photoUploadPending || createdItem !== null

  function handleDialogOpenChange(nextOpen: boolean) {
    if (nextOpen && !canCreate) return
    if (!nextOpen && (createMutation.isPending || photoUploadPending)) return

    if (!nextOpen) {
      if (createdResult) {
        finishCreation(createdResult, form.photos)
        return
      }
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
      createdItem !== null ||
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

  return (
    <Dialog open={open && canCreate} onOpenChange={handleDialogOpenChange}>
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description ? (
            <DialogDescription>{description}</DialogDescription>
          ) : null}
        </DialogHeader>

        {!creationOptions ? (
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
                  <FieldLabel htmlFor={numberInputId}>Номер бытовки</FieldLabel>
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
                  {numberError ? <FieldError>{numberError}</FieldError> : null}
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
                disabled={createMutation.isPending || photoUploadPending}
                onClick={() => handleDialogOpenChange(false)}
              >
                {createdItem ? "Закрыть" : "Отмена"}
              </Button>
              {createdItem ? (
                <Button
                  type="button"
                  className="flex-[1.65]"
                  disabled={photoUploadPending}
                  onClick={() => {
                    if (createdResult) {
                      void uploadCreatedPhotos(createdResult, form.photos)
                    }
                  }}
                >
                  <HugeiconsIcon icon={CheckIcon} data-icon="inline-start" />
                  {photoUploadPending
                    ? "Загрузка фото..."
                    : "Повторить загрузку фото"}
                </Button>
              ) : (
                <Button
                  type="submit"
                  className="flex-[1.65]"
                  disabled={!canCreate || createMutation.isPending}
                >
                  <HugeiconsIcon icon={CheckIcon} data-icon="inline-start" />
                  {createMutation.isPending ? "Создание..." : submitLabel}
                </Button>
              )}
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
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

  return (
    <RentalItemCreationDialog<RentalItemDto>
      open={open}
      warehouseId={warehouseId}
      onOpenChange={onOpenChange}
      canCreate={canEditRentalItems}
      title="Создание новой бытовки"
      createRentalItem={async (command) => {
        const item = await createAssetRentalItem({
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
        })
        return {
          createdItem: {
            id: item.id,
            warehouseId: item.warehouseId,
            number: item.number,
          },
          value: item,
        }
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
