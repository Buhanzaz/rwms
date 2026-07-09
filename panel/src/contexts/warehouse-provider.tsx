import { useEffect, useMemo, useState, type ReactNode } from "react"

import { getWarehouses, type WarehouseInfo } from "@/api/warehouse-api"
import { WarehouseContext } from "@/contexts/warehouse-context"

const STORAGE_KEY = "wms:selected-warehouse-id"

function getSavedWarehouseId() {
  return window.localStorage.getItem(STORAGE_KEY)
}

export function WarehouseProvider({ children }: { children: ReactNode }) {
  const [warehouses, setWarehouses] = useState<WarehouseInfo[]>([])
  const [selectedWarehouseId, setSelectedWarehouseIdState] = useState<
    string | null
  >(getSavedWarehouseId)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let ignore = false

    async function loadWarehouses() {
      try {
        setIsLoading(true)
        setError(null)

        const data = await getWarehouses()
        const activeWarehouses = data.filter((warehouse) => warehouse.active)

        if (ignore) {
          return
        }

        setWarehouses(activeWarehouses)

        const savedWarehouseId = getSavedWarehouseId()

        const savedWarehouseExists = activeWarehouses.some(
          (warehouse) => warehouse.id === savedWarehouseId
        )

        if (savedWarehouseId && savedWarehouseExists) {
          setSelectedWarehouseIdState(savedWarehouseId)
          return
        }

        const firstWarehouse = activeWarehouses[0]

        if (firstWarehouse) {
          setSelectedWarehouseIdState(firstWarehouse.id)
          window.localStorage.setItem(STORAGE_KEY, firstWarehouse.id)
        }
      } catch (unknownError) {
        if (ignore) {
          return
        }

        setError(
          unknownError instanceof Error
            ? unknownError.message
            : "Ошибка загрузки складов"
        )
      } finally {
        if (!ignore) {
          setIsLoading(false)
        }
      }
    }

    loadWarehouses()

    return () => {
      ignore = true
    }
  }, [])

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
      }}
    >
      {children}
    </WarehouseContext.Provider>
  )
}
