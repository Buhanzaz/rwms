import { createOrderIdempotencyKey } from "@/features/orders/api/orders-api"

export class OrderCommandIdentityRegistry {
  private readonly keys = new Map<string, string>()

  keyFor(fingerprint: string) {
    const existing = this.keys.get(fingerprint)
    if (existing) return existing

    const created = createOrderIdempotencyKey()
    this.keys.set(fingerprint, created)
    return created
  }

  confirm(fingerprint: string) {
    this.keys.delete(fingerprint)
  }

  reset() {
    this.keys.clear()
  }
}
