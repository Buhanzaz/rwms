import { fetchApi, readApiResponse } from '../../api/http-response';
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
  companyId: UUID | null;
}

export interface ContractorDriverInput {
  displayName: string;
  phone: string;
  comment: string;
  active: boolean;
  companyId: UUID | null;
}

/** City-owned company contacts; dates and route execution stay with individual drivers. */
export interface ContractorCompanyInput {
  name: string;
  inn: string;
  contactName: string | null;
  phone: string;
  email: string | null;
  address: string | null;
  comment: string | null;
}

export interface ContractorCompany extends ContractorCompanyInput {
  companyId: UUID;
  version: number;
  homeWarehouseId: UUID;
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

function contractorResponse<T>(response: Response): Promise<T> {
  return readApiResponse<T>(response, 'Не удалось выполнить запрос');
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
  const response = await fetchApi(
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
  const response = await fetchApi(
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
  const response = await fetchApi(
    `/api/task-board/warehouses/${encodeURIComponent(warehouseId)}/logistics-drivers/contractors`,
    {
      method: 'POST',
      headers: headers(accessToken, true),
      body: JSON.stringify({
        contractorId,
        displayName: input.displayName,
        phone: input.phone,
        comment: input.comment,
        companyId: input.companyId,
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
  const response = await fetchApi(
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
  const response = await fetchApi(
    `/api/task-board/warehouses/${encodeURIComponent(warehouseId)}/logistics-drivers/contractors/${encodeURIComponent(contractor.workerId)}?${query.toString()}`,
    {
      method: 'DELETE',
      headers: headers(accessToken),
    },
  );
  if (!response.ok) await contractorResponse<never>(response);
}

function companyPath(warehouseId: UUID): string {
  return `/api/task-board/warehouses/${encodeURIComponent(warehouseId)}/logistics-drivers/companies`;
}

export async function listContractorCompanies(accessToken: string, warehouseId: UUID): Promise<ContractorCompany[]> {
  return contractorResponse<ContractorCompany[]>(await fetchApi(companyPath(warehouseId), { headers: headers(accessToken) }));
}

/** The editor retains companyId across retries; the owner checks identical create replays. */
export async function createContractorCompany(accessToken: string, warehouseId: UUID, input: ContractorCompanyInput, companyId: UUID): Promise<ContractorCompany> {
  return contractorResponse<ContractorCompany>(await fetchApi(companyPath(warehouseId), {
    method: 'POST', headers: headers(accessToken, true), body: JSON.stringify({ companyId, ...input }),
  }));
}

export async function updateContractorCompany(accessToken: string, warehouseId: UUID, company: ContractorCompany, input: ContractorCompanyInput): Promise<ContractorCompany> {
  return contractorResponse<ContractorCompany>(await fetchApi(`${companyPath(warehouseId)}/${encodeURIComponent(company.companyId)}`, {
    method: 'PATCH', headers: headers(accessToken, true), body: JSON.stringify({ expectedVersion: company.version, ...input }),
  }));
}

/** The service refuses deletion while any driver still belongs to the company. */
export async function deleteContractorCompany(accessToken: string, warehouseId: UUID, company: ContractorCompany): Promise<void> {
  const query = new URLSearchParams({ expectedVersion: String(company.version) });
  const response = await fetchApi(`${companyPath(warehouseId)}/${encodeURIComponent(company.companyId)}?${query}`, {
    method: 'DELETE', headers: headers(accessToken),
  });
  if (!response.ok) await contractorResponse<never>(response);
}
