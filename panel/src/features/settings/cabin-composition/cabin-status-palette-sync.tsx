import { useEffect } from "react"
import { toast } from "sonner"
import { useAuth } from "@/features/auth/use-auth"
import {
  CABIN_STATUS_COLOR_STATUSES,
  cabinStatusColorVariable,
  useCabinStatusColors,
} from "./cabin-status-colors-api"

/** Applies the server palette to portals as well as pages, and removes it at the authentication boundary. */
export function CabinStatusPaletteSync() {
  const { accessToken, currentUser } = useAuth()
  const query = useCabinStatusColors()
  const { error, refetch } = query
  const palette =
    accessToken && currentUser && !query.error ? query.data : undefined
  useEffect(() => {
    if (!palette) return
    const style = document.documentElement.style
    CABIN_STATUS_COLOR_STATUSES.forEach((status) =>
      style.setProperty(
        cabinStatusColorVariable(status),
        palette.colors[status]
      )
    )
    return () =>
      CABIN_STATUS_COLOR_STATUSES.forEach((status) =>
        style.removeProperty(cabinStatusColorVariable(status))
      )
  }, [palette])
  useEffect(() => {
    if (!error) {
      toast.dismiss("cabin-status-palette-error")
      return
    }
    toast.error("Общие цвета статусов недоступны. Показана базовая палитра.", {
      id: "cabin-status-palette-error",
      description: error.message,
      action: { label: "Повторить", onClick: () => void refetch() },
    })
  }, [error, refetch])
  return null
}
