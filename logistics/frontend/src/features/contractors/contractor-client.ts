import { ApiError, type ProblemDetails } from '../../api/client';
import type { UUID } from '../../domain/types';

/** Task-board-owned contractor profile used by dispatcher handoff forms. */
export interface ContractorDriver {
  workerId: UUID;
  version: number;
  homeWarehouseId: UUID;
  displayName: string;
  phone: string;
  comment: string | null;
  active: boolean;
  employmentType: 'CONTRACTOR';
}

export interface ContractorDriverInput {
  displayName: string;
  phone: string;
  comment: string;
  active: boolean;
}

/** Exact canonical task identities used to create one explicit contractor route link. */
export interface ContractorRouteShareInput {
  contractorWorkerId: UUID;
  expiresAt: string;
  externalTaskIds: UUID[];
}

/** Expiring same-origin route link returned by the logistics owner. */
export interface ContractorRouteShare {
  id: UUID;
  version: number;
  warehouseId: UUID;
  contractorWorkerId: UUID;
  expiresAt: string;
  revokedAt: string | null;
  createdAt: string;
  publicPath: string | null;
  externalTaskIds: UUID[];
}

async function contractorResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let problem: ProblemDetails | null = null;
    try {
      const value: unknown = await response.json();
      if (value && typeof value === 'object') problem = value;
    } catch {
      problem = null;
    }
    throw new ApiError(response.status, problem, 'Не удалось выполнить запрос');
  }
  return response.json() as Promise<T>;
}

function headers(accessToken: string, withBody = false): HeadersInit {
  return {
    Accept: 'application/json, application/problem+json',
    Authorization: `Bearer ${accessToken}`,
    ...(withBody ? { 'Content-Type': 'application/json' } : {}),
  };
}

/** Creates or replays one explicit contractor route share through the public gateway. */
export async function createContractorRouteShare(
  accessToken: string,
  warehouseId: UUID,
  input: ContractorRouteShareInput,
  idempotencyKey: UUID,
): Promise<ContractorRouteShare> {
  const response = await fetch(
    `/api/logistics/v1/warehouses/${encodeURIComponent(warehouseId)}/contractor-route-shares`,
    {
      method: 'POST',
      headers: {
        ...headers(accessToken, true),
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify(input),
    },
  );
  return contractorResponse<ContractorRouteShare>(response);
}

/** Loads the complete warehouse-owned contractor catalog, including inactive profiles. */
export async function listContractorDrivers(
  accessToken: string,
  warehouseId: UUID,
): Promise<ContractorDriver[]> {
  const response = await fetch(
    `/api/task-board/warehouses/${encodeURIComponent(warehouseId)}/logistics-drivers/contractors`,
    { headers: headers(accessToken) },
  );
  return contractorResponse<ContractorDriver[]>(response);
}

/** Creates a reusable contractor profile without a staff vehicle, shift, or availability period. */
export async function createContractorDriver(
  accessToken: string,
  warehouseId: UUID,
  input: ContractorDriverInput,
  contractorId: UUID = crypto.randomUUID(),
): Promise<ContractorDriver> {
  const response = await fetch(
    `/api/task-board/warehouses/${encodeURIComponent(warehouseId)}/logistics-drivers/contractors`,
    {
      method: 'POST',
      headers: headers(accessToken, true),
      body: JSON.stringify({
        contractorId,
        displayName: input.displayName,
        phone: input.phone,
        comment: input.comment,
      }),
    },
  );
  return contractorResponse<ContractorDriver>(response);
}

/** Updates one contractor behind task-board optimistic version fencing. */
export async function updateContractorDriver(
  accessToken: string,
  warehouseId: UUID,
  contractor: ContractorDriver,
  input: ContractorDriverInput,
): Promise<ContractorDriver> {
  const response = await fetch(
    `/api/task-board/warehouses/${encodeURIComponent(warehouseId)}/logistics-drivers/contractors/${encodeURIComponent(contractor.workerId)}`,
    {
      method: 'PATCH',
      headers: headers(accessToken, true),
      body: JSON.stringify({ expectedVersion: contractor.version, ...input }),
    },
  );
  return contractorResponse<ContractorDriver>(response);
}

/** Deletes an unused contractor profile behind task-board optimistic version fencing. */
export async function deleteContractorDriver(
  accessToken: string,
  warehouseId: UUID,
  contractor: ContractorDriver,
): Promise<void> {
  const query = new URLSearchParams({ expectedVersion: String(contractor.version) });
  const response = await fetch(
    `/api/task-board/warehouses/${encodeURIComponent(warehouseId)}/logistics-drivers/contractors/${encodeURIComponent(contractor.workerId)}?${query.toString()}`,
    {
      method: 'DELETE',
      headers: headers(accessToken),
    },
  );
  if (!response.ok) await contractorResponse<never>(response);
}
