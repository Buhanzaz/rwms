import { useCallback } from "react"
import {
  useLocation,
  useNavigate,
  type NavigateOptions,
  type To,
} from "react-router-dom"

const WORKSPACE_ENTRY_STATE_KEY = "rwmsWorkspaceEntry"

export const workspaceEntryNavigationOptions = {
  state: { [WORKSPACE_ENTRY_STATE_KEY]: true },
} satisfies NavigateOptions

function isWorkspaceEntryState(value: unknown) {
  return (
    typeof value === "object" &&
    value !== null &&
    WORKSPACE_ENTRY_STATE_KEY in value &&
    value[WORKSPACE_ENTRY_STATE_KEY as keyof typeof value] === true
  )
}

export function useWorkspaceBack(fallback: To) {
  const location = useLocation()
  const navigate = useNavigate()
  const enteredInsideApp = isWorkspaceEntryState(location.state)

  return useCallback(() => {
    if (enteredInsideApp) {
      navigate(-1)
      return
    }

    navigate(fallback, { replace: true })
  }, [enteredInsideApp, fallback, navigate])
}
