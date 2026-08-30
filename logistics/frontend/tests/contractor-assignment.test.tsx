import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ContractorAssignmentDialog } from '../src/features/contractors/ContractorAssignmentDialog';
import { ContractorDriversPanel } from '../src/features/contractors/ContractorDriversPanel';
import { requestFixture } from './fixtures';

const contractorApi = vi.hoisted(() => ({ list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() }));

vi.mock('../src/auth/panel-oidc', () => ({
  restorePanelUser: () => Promise.resolve({ access_token: 'token' }),
  beginPanelLogin: vi.fn(),
}));

vi.mock('../src/features/contractors/contractor-client', () => ({
  listContractorDrivers: contractorApi.list,
  createContractorDriver: contractorApi.create,
  updateContractorDriver: contractorApi.update,
  deleteContractorDriver: contractorApi.remove,
}));

describe('contractor assignment dialog', () => {
  beforeEach(() => {
    contractorApi.list.mockReset();
    contractorApi.create.mockReset();
    contractorApi.update.mockReset();
    contractorApi.remove.mockReset();
    contractorApi.list.mockResolvedValue([
      {
        workerId: 'contractor-active', version: 0, homeWarehouseId: 'external-warehouse',
        displayName: 'Иван Петров', phone: '+7 900 000-00-00',
        comment: 'Подрядчик', active: true, employmentType: 'CONTRACTOR',
      },
      {
        workerId: 'contractor-inactive', version: 0, homeWarehouseId: 'external-warehouse',
        displayName: 'Неактивный водитель', phone: '+7 900 111-00-00',
        comment: null, active: false, employmentType: 'CONTRACTOR',
      },
      {
        workerId: 'contractor-late', version: 0, homeWarehouseId: 'external-warehouse',
        displayName: 'Поздний водитель', phone: '+7 900 222-00-00',
        comment: null, active: true, employmentType: 'CONTRACTOR',
      },
    ]);
  });

  it('lists active contractor profiles and confirms a direct handoff', async () => {
    const user = userEvent.setup();
    const onAssign = vi.fn(() => Promise.resolve());
    render(<ContractorAssignmentDialog
      request={requestFixture({
        scheduled_date: '2026-08-31',
        date_options: [{
          date: '2026-08-31', priority: 1, window_start: '10:00', window_end: '14:00', is_hard: true,
        }],
      })}
      warehouseId="external-warehouse"
      busy={false}
      onClose={() => undefined}
      onAssign={onAssign}
    />);

    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Наёмный водитель' })).toHaveDisplayValue(/Иван Петров/));
    expect(screen.queryByText('Неактивный водитель')).not.toBeInTheDocument();
    expect(screen.getByText(/Поздний водитель/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/машин|вместимость|цикл/iu)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Подтвердить передачу' }));
    expect(onAssign).toHaveBeenCalledWith('contractor-active');
  });

  it('creates a contractor profile without asking for a machine or route settings', async () => {
    const user = userEvent.setup();
    contractorApi.list.mockResolvedValue([]);
    contractorApi.create.mockResolvedValue({});
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-08-31" manualRequests={[]} busy={false} onDispatch={() => Promise.resolve()} />);

    await user.click(await screen.findByRole('button', { name: 'Добавить' }));
    await user.type(screen.getByLabelText('Имя / название'), 'Иван Петров');
    await user.type(screen.getByLabelText('Телефон'), '+7 900 000-00-00');
    expect(screen.queryByLabelText(/машин|вместимость|цикл/iu)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/доступен с|доступен до/iu)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(contractorApi.create).toHaveBeenCalledWith(
      'token',
      'external-warehouse',
      expect.objectContaining({ displayName: 'Иван Петров', phone: '+7 900 000-00-00', active: true }),
      expect.any(String),
    ));
  });

  it('uses the header date and exposes automatic and manual dispatch actions', async () => {
    const user = userEvent.setup();
    const onDispatch = vi.fn(() => Promise.resolve());
    const request = requestFixture({
      id: 'request-manual',
      warehouse_id: 'local-warehouse',
      scheduled_date: '2026-08-31',
      status: 'UNASSIGNED',
      name: '№31',
    });
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-08-31" manualRequests={[request]} busy={false} onDispatch={onDispatch} />);

    expect(await screen.findByText(/31 августа 2026/)).toBeVisible();
    await user.click(screen.getAllByRole('button', { name: 'Сформировать рейс' })[0]!);
    await waitFor(() => expect(onDispatch).toHaveBeenCalledWith('contractor-active', 'AUTO', []));

    await user.click(screen.getAllByRole('button', { name: 'Распределить вручную' })[0]!);
    const dialog = await screen.findByRole('dialog', { name: 'Распределить вручную' });
    await user.click(dialog.querySelector('input[type="checkbox"]')!);
    await user.click(screen.getByRole('button', { name: 'Передать выбранные (1)' }));
    await waitFor(() => expect(onDispatch).toHaveBeenCalledWith('contractor-active', 'MANUAL', ['request-manual']));
  });

  it('deletes a contractor only from the edit dialog after confirmation', async () => {
    const user = userEvent.setup();
    contractorApi.remove.mockResolvedValue(undefined);
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-08-31" manualRequests={[]} busy={false} onDispatch={() => Promise.resolve()} />);

    await user.click((await screen.findAllByRole('button', { name: 'Изменить' }))[0]!);
    await user.click(screen.getByRole('button', { name: 'Удалить' }));
    await user.click(screen.getByRole('button', { name: 'Удалить водителя' }));
    await waitFor(() => expect(contractorApi.remove).toHaveBeenCalledWith(
      'token',
      'external-warehouse',
      expect.objectContaining({ workerId: 'contractor-active' }),
    ));
  });
});
