import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createContractorDriver,
  deleteContractorDriver,
  listContractorDrivers,
  updateContractorDriver,
  type ContractorDriver,
} from '../src/features/contractors/contractor-client';

const contractor: ContractorDriver = {
  workerId: '22222222-2222-4222-8222-222222222222',
  version: 3,
  homeWarehouseId: '11111111-1111-4111-8111-111111111111',
  displayName: 'Иван Петров',
  phone: '+7 900 000-00-00',
  comment: 'Подрядчик',
  active: true,
  employmentType: 'CONTRACTOR',
};

describe('task-board contractor client', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('loads the complete warehouse-owned contractor catalog', async () => {
    const fetchMock = vi.fn<typeof fetch>(() => Promise.resolve(new Response(JSON.stringify([contractor]), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })));
    vi.stubGlobal('fetch', fetchMock);

    await expect(listContractorDrivers('access-token', contractor.homeWarehouseId)).resolves.toEqual([contractor]);
    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(url).toBe(`/api/task-board/warehouses/${contractor.homeWarehouseId}/logistics-drivers/contractors`);
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer access-token');
  });

  it('creates, updates and deletes a contractor without dates, vehicle or cycle fields', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...contractor, version: 0 }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...contractor, active: false, version: 4 }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      displayName: contractor.displayName,
      phone: contractor.phone,
      comment: contractor.comment ?? '',
      active: true,
    };

    await createContractorDriver('token', contractor.homeWarehouseId, input, 'contractor-intent-id');
    const createInit = fetchMock.mock.calls[0]?.[1];
    expect(createInit?.method).toBe('POST');
    if (typeof createInit?.body !== 'string') throw new Error('Expected a JSON request body');
    const createBody: unknown = JSON.parse(createInit.body);
    expect(createBody).not.toHaveProperty('vehicle');
    expect(createBody).not.toHaveProperty('capacity');
    expect(createBody).not.toHaveProperty('cycles');
    expect(createBody).not.toHaveProperty('availableFrom');
    expect(createBody).not.toHaveProperty('availableUntil');
    expect(createBody).toHaveProperty('contractorId', 'contractor-intent-id');

    await updateContractorDriver('token', contractor.homeWarehouseId, contractor, { ...input, active: false });
    const updateInit = fetchMock.mock.calls[1]?.[1];
    expect(updateInit?.method).toBe('PATCH');
    if (typeof updateInit?.body !== 'string') throw new Error('Expected a JSON request body');
    const updateBody: unknown = JSON.parse(updateInit.body);
    expect(updateBody).toMatchObject({ expectedVersion: 3, active: false });
    expect(updateBody).not.toHaveProperty('availableFrom');
    expect(updateBody).not.toHaveProperty('availableUntil');

    await deleteContractorDriver('token', contractor.homeWarehouseId, contractor);
    const [deleteUrl, deleteInit] = fetchMock.mock.calls[2] ?? [];
    expect(deleteUrl).toBe(`/api/task-board/warehouses/${contractor.homeWarehouseId}/logistics-drivers/contractors/${contractor.workerId}?expectedVersion=3`);
    expect(deleteInit?.method).toBe('DELETE');
  });
});
