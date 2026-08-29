/** Complete zero-or-more-cargo plan sent while the canonical transfer document is still a draft. */
export interface TransferPlanDraftInput {
  plannedDepartureAt: string | null;
  plannedArrivalAt: string | null;
  logisticsComment: string | null;
  tripDriverId: string | null;
  tripVehicleId: string | null;
  driverReposition: null;
  vehicleReposition: null;
  cabinGroups: [];
  looseFurniture: [];
}

/** Authenticated, idempotent command for one exact canonical transfer-draft intention. */
export interface CreateTransferDraftInput {
  accessToken: string;
  idempotencyKey: string;
  warehouseId: string;
  destinationWarehouseId: string;
  scheduledDate: string;
  plan: TransferPlanDraftInput;
}

/** Minimum transfer-document identity needed by the standalone success feedback. */
export interface CreatedTransferDraft {
  id: string;
  version?: number;
  state?: string;
}

function canonicalProblemMessage(value: unknown, fallback: string) {
  if (!value || typeof value !== 'object') return fallback;
  const problem = value as Record<string, unknown>;
  if (typeof problem.detail === 'string') return problem.detail;
  if (typeof problem.title === 'string') return problem.title;
  return fallback;
}

/** Creates the authoritative logistics-service draft through the public same-origin gateway. */
export async function createTransferDraft(input: CreateTransferDraftInput): Promise<CreatedTransferDraft> {
  const response = await fetch('/api/logistics/v1/transfers', {
    method: 'POST',
    headers: {
      Accept: 'application/json, application/problem+json',
      Authorization: `Bearer ${input.accessToken}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': input.idempotencyKey,
    },
    body: JSON.stringify({
      warehouseId: input.warehouseId,
      destinationWarehouseId: input.destinationWarehouseId,
      scheduledDate: input.scheduledDate,
      lines: [],
      furnitureReplacements: [],
      plan: input.plan,
    }),
  });
  if (!response.ok) {
    let problem: unknown = null;
    try {
      problem = await response.json();
    } catch {
      problem = null;
    }
    throw new Error(canonicalProblemMessage(problem, `Не удалось создать перемещение (HTTP ${response.status})`));
  }
  const value: unknown = await response.json();
  if (!value || typeof value !== 'object' || typeof (value as Record<string, unknown>).id !== 'string') {
    throw new Error('Логистика вернула некорректный черновик перемещения');
  }
  return value as CreatedTransferDraft;
}
