import { QueryClient } from "@tanstack/react-query"

import { clearMediaPreviewCache } from "@/features/media/media-preview-cache"

/** Identifies the verified panel subject and bearer-grant revision for one session. */
export type ProtectedPrincipalGrant = Readonly<{
  subjectId: string
  grantRevision: string
}>

/** Holds the TanStack client that is safe to expose to one protected UI revision. */
export type ProtectedClientSnapshot = Readonly<{
  queryClient: QueryClient
  revision: number
}>

/** Tracks resources that belong to the currently authenticated client revision. */
type ActiveProtectedClientState = {
  principal: ProtectedPrincipalGrant
}

/**
 * Owns the browser-only boundary between two authenticated principals or grant
 * revisions. A fresh query client fences late completions from the retired
 * session, while the root client remains available to public/bootstrap routes.
 */
export class ProtectedClientState {
  private readonly createQueryClient: () => QueryClient
  private active: ActiveProtectedClientState | null = null
  private revision = 0
  private snapshotValue: ProtectedClientSnapshot
  private transition: Promise<void> = Promise.resolve()

  constructor(createQueryClient: () => QueryClient = () => new QueryClient()) {
    this.createQueryClient = createQueryClient
    this.snapshotValue = this.createSnapshot()
  }

  /** Returns the client currently assigned to the protected React subtree. */
  get snapshot() {
    return this.snapshotValue
  }

  /**
   * Activates the given verified principal. A changed subject or bearer-grant
   * revision closes the previous protected client before a fresh one is
   * published; an unchanged revision keeps its in-memory work intact.
   */
  activate(principal: ProtectedPrincipalGrant) {
    return this.enqueue(async () => {
      if (this.active === null) {
        this.active = { principal }
        return this.snapshotValue
      }

      if (samePrincipalGrant(this.active.principal, principal)) {
        return this.snapshotValue
      }

      await this.closeActiveState()
      this.snapshotValue = this.createSnapshot()
      this.active = { principal }
      return this.snapshotValue
    })
  }

  /**
   * Closes the current principal boundary for logout or an invalid session.
   * The next protected render receives an empty client rather than a cleared
   * client that a late callback could still hold.
   */
  deactivate() {
    return this.enqueue(async () => {
      const hadActivePrincipal = this.active !== null
      await this.closeActiveState()
      if (hadActivePrincipal) this.snapshotValue = this.createSnapshot()
      else clearMediaPreviewCache()
      return this.snapshotValue
    })
  }

  private createSnapshot(): ProtectedClientSnapshot {
    this.revision += 1
    return {
      queryClient: this.createQueryClient(),
      revision: this.revision,
    }
  }

  private enqueue<T>(operation: () => Promise<T>) {
    const result = this.transition.then(operation, operation)
    this.transition = result.then(
      () => undefined,
      () => undefined
    )
    return result
  }

  private async closeActiveState() {
    const active = this.active
    this.active = null
    if (!active) return

    const queryClient = this.snapshotValue.queryClient
    const cancellation = queryClient.cancelQueries()
    queryClient.removeQueries()
    queryClient.getMutationCache().clear()
    clearMediaPreviewCache()
    await cancellation
  }
}

function samePrincipalGrant(
  left: ProtectedPrincipalGrant | undefined,
  right: ProtectedPrincipalGrant
) {
  return (
    left?.subjectId === right.subjectId &&
    left.grantRevision === right.grantRevision
  )
}
