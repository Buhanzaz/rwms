import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react"

import { listWarehouses, type WarehouseInfo } from "@/api/warehouse-api"
import { WarehouseContext } from "@/contexts/warehouse-context"
import { resolveWarehouseSelection } from "@/contexts/warehouse-selection"
import { useAuth } from "@/features/auth/use-auth"

const STORAGE_KEY = "wms:selected-warehouse-id"

function getSavedWarehouseId() {
  return window.localStorage.getItem(STORAGE_KEY)
}

export function WarehouseProvider({ children }: { children: ReactNode }) {
  const { accessToken } = useAuth()
  const [warehouses, setWarehouses] = useState<WarehouseInfo[]>([])
  const [selectedWarehouseId, setSelectedWarehouseIdState] = useState<
    string | null
  >(getSavedWarehouseId)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const reloadWarehouses = useCallback(async () => {
    try {
      setIsLoading(true)
      setError(null)

      const data = await listWarehouses(accessToken)
      const activeWarehouses = data.filter((warehouse) => warehouse.active)
      const savedWarehouseId = getSavedWarehouseId()
      const nextSelectedWarehouseId = resolveWarehouseSelection(
        savedWarehouseId,
        activeWarehouses
      )

      setWarehouses(activeWarehouses)
      setSelectedWarehouseIdState(nextSelectedWarehouseId)

      if (nextSelectedWarehouseId === null) {
        window.localStorage.removeItem(STORAGE_KEY)
      } else if (savedWarehouseId !== nextSelectedWarehouseId) {
        window.localStorage.setItem(STORAGE_KEY, nextSelectedWarehouseId)
      }
    } catch (unknownError) {
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Ошибка загрузки складов"
      )
    } finally {
      setIsLoading(false)
    }
  }, [accessToken])

  useEffect(() => {
    let mounted = true

    void Promise.resolve().then(() => {
      if (mounted) {
        return reloadWarehouses()
      }

      return undefined
    })

    return () => {
      mounted = false
    }
  }, [reloadWarehouses])

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
