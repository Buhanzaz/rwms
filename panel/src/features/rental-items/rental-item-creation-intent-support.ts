import type {
  RentalItemCreationIntent,
  RentalItemCreationPhotoManifestEntry,
  RentalItemCreationPhotoManifestInput,
} from "@/features/rental-items/api/asset-rental-items-api"
import type {
  MediaClient,
  MediaUploadCommandKeys,
} from "@/features/media/media-service"
import type {
  MediaAsset,
  ServiceMediaOwner,
} from "@/features/media/model/service-media"
import type { StagedRentalItemPhoto } from "@/features/rental-items/rental-item-creation-photo-uploader"

const MAX_CREATION_PHOTOS = 20
const READY_POLL_ATTEMPTS = 20
const READY_POLL_INITIAL_DELAY_MS = 250
const READY_POLL_MAX_DELAY_MS = 2_000
const CREATION_IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/webp"])

/** Ordered local files and their immutable source proof prepared before create. */
export type PreparedRentalItemCreationPhotos = Readonly<{
  photos: readonly StagedRentalItemPhoto[]
  manifest: readonly RentalItemCreationPhotoManifestInput[]
}>

/** Result of comparing reselected local bytes with a durable server manifest. */
export type RentalItemCreationManifestMatch =
  Readonly<{ matches: true }> | Readonly<{ matches: false; message: string }>

/** Runtime dependencies for bounded READY-state verification. */
export type RentalItemCreationReadyCheck = Readonly<{
  mediaClient: MediaClient
  accessToken: string
  owner: ServiceMediaOwner
  folderId: string
  uploadedAssets: readonly MediaAsset[]
  delay?: (milliseconds: number) => Promise<void>
  attempts?: number
}>

/** Puts the selected title image at manifest index zero. */
export function orderRentalItemCreationPhotos(
  photos: readonly StagedRentalItemPhoto[]
) {
  const titlePhoto = photos.find((photo) => photo.title)
  if (!titlePhoto) return [...photos]
  return [titlePhoto, ...photos.filter((photo) => photo.id !== titlePhoto.id)]
}

/** Computes the exact ordered manifest before asset-service creates a cabin. */
export async function prepareRentalItemCreationPhotos(
  mediaClient: MediaClient,
  photos: readonly StagedRentalItemPhoto[]
): Promise<PreparedRentalItemCreationPhotos> {
  const orderedPhotos = orderRentalItemCreationPhotos(photos)
  if (
    orderedPhotos.length < 1 ||
    orderedPhotos.length > MAX_CREATION_PHOTOS ||
    orderedPhotos.filter((photo) => photo.title).length !== 1
  ) {
    throw new Error(
      "Для создания бытовки выберите от 1 до 20 фотографий и одно титульное фото."
    )
  }

  const manifest = await Promise.all(
    orderedPhotos.map(async (photo, photoIndex) => {
      const contentType = photo.file.type.toLowerCase()
      if (
        photo.file.size <= 0 ||
        !Number.isSafeInteger(photo.file.size) ||
        !CREATION_IMAGE_TYPES.has(contentType)
      ) {
        throw new Error(
          "Одна из фотографий имеет неподдерживаемый формат или размер. Выберите JPEG, PNG или WebP."
        )
      }
      return {
        photoIndex,
        checksumSha256: await mediaClient.calculateChecksumSha256(photo.file),
        contentType:
          contentType as RentalItemCreationPhotoManifestInput["contentType"],
        contentLength: photo.file.size,
      }
    })
  )

  return { photos: orderedPhotos, manifest }
}

/** Compares exact bytes, order and metadata without relying on filenames. */
export function matchRentalItemCreationManifest(
  prepared: PreparedRentalItemCreationPhotos,
  intent: RentalItemCreationIntent
): RentalItemCreationManifestMatch {
  if (
    intent.state !== "PENDING" ||
    prepared.manifest.length !== intent.expectedPhotoCount ||
    prepared.manifest.length !== intent.photoManifest.length
  ) {
    return {
      matches: false,
      message: `Для продолжения выберите ровно ${intent.expectedPhotoCount} фото из исходного набора в том же порядке и назначьте прежнее титульное фото.`,
    }
  }

  const mismatch = prepared.manifest.some((photo, index) => {
    const expected = intent.photoManifest[index]
    return (
      expected === undefined ||
      expected.photoIndex !== photo.photoIndex ||
      expected.checksumSha256 !== photo.checksumSha256 ||
      expected.contentType !== photo.contentType ||
      expected.contentLength !== photo.contentLength
    )
  })
  return mismatch
    ? {
        matches: false,
        message:
          "Выбранные фотографии не совпадают с сохранённым составом. Выберите исходные файлы, сохраните их порядок и прежнее титульное фото.",
      }
    : { matches: true }
}

/** Uses the server-owned per-photo UUID for every command-type-scoped replay. */
export function creationPhotoCommandKeys(
  entry: RentalItemCreationPhotoManifestEntry
): MediaUploadCommandKeys {
  return {
    createSession: entry.uploadCommandId,
    uploadAndFinalize: entry.uploadCommandId,
  }
}

/** Rejects a create response that does not describe the exact held cabin. */
export function validateCreatedRentalItemIntent(
  intent: RentalItemCreationIntent,
  rentalItem: Readonly<{ id: string; warehouseId: string }>,
  prepared: PreparedRentalItemCreationPhotos
) {
  const manifestMatch = matchRentalItemCreationManifest(prepared, intent)
  if (
    intent.rentalItemId !== rentalItem.id ||
    intent.warehouseId !== rentalItem.warehouseId ||
    !manifestMatch.matches
  ) {
    throw new Error(
      "Сервис создал незавершённую бытовку, но вернул другой состав фотографий. Загрузка не начата; продолжите через список незавершённых созданий."
    )
  }
}

/** Waits until every uploaded intent asset is current and READY. */
export async function waitForRentalItemCreationPhotosReady({
  mediaClient,
  accessToken,
  owner,
  folderId,
  uploadedAssets,
  delay = defaultDelay,
  attempts = READY_POLL_ATTEMPTS,
}: RentalItemCreationReadyCheck): Promise<void> {
  const expectedIds = new Set(uploadedAssets.map((asset) => asset.id))
  if (
    expectedIds.size !== uploadedAssets.length ||
    uploadedAssets.some((asset) => asset.folderId !== folderId)
  ) {
    throw new Error(
      "Сервис фото вернул неверную папку или повторяющийся идентификатор. Создание осталось незавершённым."
    )
  }
  if (uploadedAssets.every(isReadyAsset)) return

  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const page = await mediaClient.listOwnerMedia(accessToken, owner, {
      limit: 100,
    })
    const current = page.items.filter((asset) => expectedIds.has(asset.id))
    if (current.some((asset) => asset.status === "FAILED")) {
      throw new Error(
        "Не удалось обработать одну из фотографий. Выберите исходные файлы и повторите загрузку."
      )
    }
    if (
      current.length === expectedIds.size &&
      current.every(
        (asset) => asset.folderId === folderId && isReadyAsset(asset)
      )
    ) {
      return
    }
    if (attempt + 1 < attempts) {
      await delay(
        Math.min(
          READY_POLL_INITIAL_DELAY_MS * 2 ** attempt,
          READY_POLL_MAX_DELAY_MS
        )
      )
    }
  }

  throw new Error(
    "Фотографии загружены, но ещё обрабатываются. Создание осталось незавершённым — откройте его позже и повторите завершение."
  )
}

function isReadyAsset(asset: MediaAsset) {
  return asset.status === "READY" && asset.generation > 0
}

function defaultDelay(milliseconds: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, milliseconds))
}
