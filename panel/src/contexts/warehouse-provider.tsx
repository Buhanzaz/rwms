import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react"

import { listWarehouses, type WarehouseInfo } from "@/api/warehouse-api"
import { WarehouseContext } from "@/contexts/warehouse-context"
import { resolveWarehouseSelection } from "@/contexts/warehouse-selection"
import type { CurrentUser } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"

const STORAGE_KEY = "wms:selected-warehouse-id"

function getSavedWarehouseId() {
  return window.localStorage.getItem(STORAGE_KEY)
}

function filterWarehousesByAccess(
  warehouses: WarehouseInfo[],
  currentUser: CurrentUser | null
) {
  if (currentUser === null) {
    return []
  }

  if (currentUser.warehouseAccessAll) {
    return warehouses
  }

  const accessibleWarehouseIds = new Set(
    currentUser.warehouseAccesses.map((access) => access.warehouseId)
  )

  return warehouses.filter((warehouse) =>
    accessibleWarehouseIds.has(warehouse.id)
  )
}

export function WarehouseProvider({ children }: { children: ReactNode }) {
  const { accessToken, currentUser } = useAuth()
  const accessTokenRef = useRef(accessToken)
  const hasLoadedWarehouseListRef = useRef(false)
  const reloadInProgressRef = useRef(false)
  const reloadQueuedRef = useRef(false)
  const [reloadRevision, setReloadRevision] = useState(0)
  const [warehouseCatalog, setWarehouseCatalog] = useState<WarehouseInfo[]>([])
  const [selectedWarehouseId, setSelectedWarehouseIdState] = useState<
    string | null
  >(getSavedWarehouseId)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    accessTokenRef.current = accessToken
  }, [accessToken])

  const reloadWarehouses = useCallback(async () => {
    if (reloadInProgressRef.current) {
      reloadQueuedRef.current = true
      return
    }

    const currentAccessToken = accessTokenRef.current

    if (currentAccessToken === null) {
      // A missing token can be a short OIDC restore/renewal window. Do not
      // discard a working warehouse selection or turn an open form into the
      // global error page while that window is in progress.
      if (!hasLoadedWarehouseListRef.current) {
        setIsLoading(true)
        setError(null)
      }
      return
    }

    const isInitialLoad = !hasLoadedWarehouseListRef.current
    reloadInProgressRef.current = true

    try {
      // AppLayout gates on these fields. A refresh after a successful load
      // must stay in the background so unsaved page state remains mounted.
      if (isInitialLoad) {
        setIsLoading(true)
        setError(null)
      }

      const data = await listWarehouses(currentAccessToken)
      const availableWarehouses = data.filter(
        (warehouse) => warehouse.lifecycleState !== "INACTIVE"
      )
      const accessibleWarehouses = filterWarehousesByAccess(
        availableWarehouses,
        currentUser
      )
      const savedWarehouseId = getSavedWarehouseId()
      const nextSelectedWarehouseId = resolveWarehouseSelection(
        savedWarehouseId,
        accessibleWarehouses
      )

      setWarehouseCatalog(availableWarehouses)
      setSelectedWarehouseIdState(nextSelectedWarehouseId)

      if (nextSelectedWarehouseId === null) {
        window.localStorage.removeItem(STORAGE_KEY)
      } else if (savedWarehouseId !== nextSelectedWarehouseId) {
        window.localStorage.setItem(STORAGE_KEY, nextSelectedWarehouseId)
      }

      hasLoadedWarehouseListRef.current = true
      setError(null)
    } catch (unknownError) {
      // A failed refresh must not replace an already usable application with
      // the global error page. The next explicit reload or a fresh session
      // token can retry it.
      if (!hasLoadedWarehouseListRef.current) {
        setError(
          unknownError instanceof Error
            ? unknownError.message
            : "Ошибка загрузки складов"
        )
      }
    } finally {
      reloadInProgressRef.current = false

      if (isInitialLoad) {
        setIsLoading(false)
      }

      // If OIDC supplied a newer token while the first request was pending,
      // retry once with that token only when the first request did not produce
      // a usable list. A successful first request remains a single load.
      const shouldRetryQueuedReload =
        reloadQueuedRef.current && !hasLoadedWarehouseListRef.current
      reloadQueuedRef.current = false

      if (shouldRetryQueuedReload) {
        setReloadRevision((current) => current + 1)
      }
    }
  }, [currentUser])

  useEffect(() => {
    // Do not perform a request until an actual bearer token is available.
    // This also retries the first load when the token appears after the
    // provider mounted. Once a list was loaded, a token rotation deliberately
    // does not reload it or remount the application.
    if (accessToken !== null && !hasLoadedWarehouseListRef.current) {
      void reloadWarehouses()
    }
  }, [accessToken, reloadRevision, reloadWarehouses])

  const warehouses = useMemo(
    () => filterWarehousesByAccess(warehouseCatalog, currentUser),
    [currentUser, warehouseCatalog]
  )

  useEffect(() => {
    if (!hasLoadedWarehouseListRef.current) {
      return
    }

    const savedWarehouseId = getSavedWarehouseId()
    const nextSelectedWarehouseId = resolveWarehouseSelection(
      savedWarehouseId,
      warehouses
    )

    setSelectedWarehouseIdState(nextSelectedWarehouseId)

    if (nextSelectedWarehouseId === null) {
      window.localStorage.removeItem(STORAGE_KEY)
    } else if (savedWarehouseId !== nextSelectedWarehouseId) {
      window.localStorage.setItem(STORAGE_KEY, nextSelectedWarehouseId)
    }
  }, [warehouses])

  const selectedWarehouse = useMemo(() => {
    if (selectedWarehouseId === null) {
      return null
    }

    return (
      warehouses.find((warehouse) => warehouse.id === selectedWarehouseId) ??
      null
    )
  }, [warehouses, selectedWarehouseId])

  function setSelectedWarehouseId(warehouseId: string) {
    const warehouseExists = warehouses.some(
      (warehouse) => warehouse.id === warehouseId
    )

    if (!warehouseExists) {
      return
    }

    setSelectedWarehouseIdState(warehouseId)
    window.localStorage.setItem(STORAGE_KEY, warehouseId)
  }

  return (
    <WarehouseContext.Provider
      value={{
        warehouses,
        selectedWarehouse,
        selectedWarehouseId,
        isLoading,
        error,
        setSelectedWarehouseId,
        reloadWarehouses,
      }}
    >
      {children}
    </WarehouseContext.Provider>
  )
}
