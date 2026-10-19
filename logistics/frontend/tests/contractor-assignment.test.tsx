import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, type ContractorDispatchResult } from '../src/api/client';
import { ContractorAssignmentDialog } from '../src/features/contractors/ContractorAssignmentDialog';
import { ContractorDriversPanel } from '../src/features/contractors/ContractorDriversPanel';
import { requestFixture } from './fixtures';

const contractorApi = vi.hoisted(() => ({ list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), share: vi.fn(), companies: vi.fn(), createCompany: vi.fn(), updateCompany: vi.fn(), removeCompany: vi.fn() }));

const company = { companyId: 'company-baltic', version: 4, homeWarehouseId: 'external-warehouse', name: 'Балтика', inn: '7801000001', contactName: 'Анна', phone: '+7 812 123-45-67', email: 'office@example.com', address: 'Московское шоссе', comment: 'Манипуляторы по звонку' };

function dispatchResult(overrides: Partial<ContractorDispatchResult> = {}): ContractorDispatchResult {
  return {
    contractor_worker_id: 'contractor-active',
    contractor_name: 'Иван Петров',
    planning_date: '2026-08-31',
    mode: 'AUTO',
    assigned_request_ids: ['request-manual'],
    assigned_count: 1,
    contractor_handoff_command_id: 'handoff-command',
    external_task_ids: ['external-task-2', 'external-task-1'],
    ...overrides,
  };
}

vi.mock('../src/auth/panel-oidc', () => ({
  restorePanelUser: () => Promise.resolve({ access_token: 'token' }),
  beginPanelLogin: vi.fn(),
}));

vi.mock('../src/features/contractors/contractor-client', () => ({
  listContractorDrivers: contractorApi.list,
  createContractorDriver: contractorApi.create,
  updateContractorDriver: contractorApi.update,
  deleteContractorDriver: contractorApi.remove,
  createContractorRouteShare: contractorApi.share,
  listContractorCompanies: contractorApi.companies,
  createContractorCompany: contractorApi.createCompany,
  updateContractorCompany: contractorApi.updateCompany,
  deleteContractorCompany: contractorApi.removeCompany,
}));

describe('contractor assignment dialog', () => {
  beforeEach(() => {
    contractorApi.companies.mockReset().mockResolvedValue([]);
    contractorApi.createCompany.mockReset().mockResolvedValue(company);
    contractorApi.updateCompany.mockReset().mockResolvedValue(company);
    contractorApi.removeCompany.mockReset().mockResolvedValue(undefined);
    contractorApi.list.mockReset();
    contractorApi.create.mockReset();
    contractorApi.update.mockReset();
    contractorApi.remove.mockReset();
    contractorApi.share.mockReset();
    contractorApi.share.mockResolvedValue({
      id: 'share-id', version: 0, warehouseId: 'external-warehouse',
      contractorWorkerId: 'contractor-active', expiresAt: '2026-09-02T00:00:00.000Z',
      revokedAt: null, createdAt: '2026-09-01T09:00:00.000Z',
      publicPath: '/contractor-routes/signed-token', externalTaskIds: ['external-task-2', 'external-task-1'],
    });
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

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('creates a company with contacts and retains the create identity after a failed request', async () => {
    const user = userEvent.setup();
    contractorApi.createCompany.mockRejectedValueOnce(new Error('Сервис недоступен'));
    render(<ContractorDriversPanel warehouseId="external-warehouse" warehouseName="SPB" planningDate="2026-09-05" manualRequests={[]} busy={false} onDispatch={vi.fn()} />);
    await user.click(await screen.findByRole('button', { name: 'Добавить компанию' }));
    const dialog = within(screen.getByRole('dialog', { name: 'Добавить наёмную компанию' }));
    await user.type(dialog.getByLabelText('Название компании'), 'Балтика');
    await user.type(dialog.getByLabelText('ИНН'), '7801000001');
    await user.type(dialog.getByLabelText('Контактное лицо'), 'Анна');
    await user.type(dialog.getByLabelText('Телефон компании'), '+7 812 123-45-67');
    await user.type(dialog.getByLabelText('Электронная почта'), 'office@example.com');
    await user.click(dialog.getByRole('button', { name: 'Сохранить компанию' }));
    expect(await dialog.findByRole('alert')).toHaveTextContent('Сервис недоступен');
    await user.click(dialog.getByRole('button', { name: 'Сохранить компанию' }));
    await waitFor(() => expect(contractorApi.createCompany).toHaveBeenCalledTimes(2));
    expect(contractorApi.createCompany.mock.calls[0]?.[3]).toEqual(contractorApi.createCompany.mock.calls[1]?.[3]);
    expect(contractorApi.createCompany.mock.calls[1]).toEqual(['token', 'external-warehouse', {
      name: 'Балтика', inn: '7801000001', contactName: 'Анна', phone: '+7 812 123-45-67',
      email: 'office@example.com', address: null, comment: null,
    }, expect.any(String)]);
  });

  it('groups drivers below company contacts and dispatches that exact driver for the selected day', async () => {
    const user = userEvent.setup();
    contractorApi.companies.mockResolvedValue([company]);
    contractorApi.list.mockResolvedValue([
      { workerId: 'company-driver', displayName: 'Петров', phone: '123', active: true, companyId: company.companyId },
      { workerId: 'independent', displayName: 'Сидоров', phone: '456', active: true, companyId: null },
    ]);
    const onDispatch = vi.fn().mockResolvedValue(undefined);
    render(<ContractorDriversPanel warehouseId="external-warehouse" warehouseName="SPB" planningDate="2026-09-05" manualRequests={[requestFixture({ scheduled_date: '2026-09-05' })]} busy={false} onDispatch={onDispatch} />);
    const card = within(await screen.findByRole('article', { name: 'Компания Балтика' }));
    expect(card.getByRole('link', { name: '+7 812 123-45-67' })).toHaveAttribute('href', 'tel:+78121234567');
    expect(card.getByRole('link', { name: 'office@example.com' })).toHaveAttribute('href', 'mailto:office@example.com');
    expect(card.getByText('Петров')).toBeVisible();
    expect(card.queryByText('Сидоров')).not.toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Водители без компании' })).getByText('Сидоров')).toBeVisible();
    expect(screen.getByText(/5 сентября 2026/)).toBeVisible();
    await user.click(card.getByRole('button', { name: 'Назначить на рейс' }));
    expect(onDispatch).toHaveBeenCalledWith('company-driver', 'AUTO', []);
  });

  it('adds a driver from the company context menu and sends explicit membership', async () => {
    const user = userEvent.setup();
    contractorApi.companies.mockResolvedValue([company]);
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-09-05" manualRequests={[]} busy={false} onDispatch={vi.fn()} />);
    fireEvent.contextMenu(await screen.findByRole('article', { name: 'Компания Балтика' }));
    await user.click(screen.getByRole('menuitem', { name: 'Добавить водителя в компанию' }));
    expect(screen.getByRole('combobox', { name: 'Компания' })).toHaveValue(company.companyId);
    await user.type(screen.getByLabelText('Имя / название'), 'Новый водитель');
    await user.type(screen.getByLabelText('Телефон'), '123');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    await waitFor(() => expect(contractorApi.create).toHaveBeenCalledWith('token', 'external-warehouse', expect.objectContaining({ companyId: company.companyId }), expect.any(String)));
  });

  it('opens company actions from the keyboard and refreshes contacts after a version conflict', async () => {
    const user = userEvent.setup();
    contractorApi.companies.mockResolvedValue([company]);
    contractorApi.updateCompany.mockRejectedValueOnce(new ApiError(409, { detail: 'Компания изменена' }, 'Конфликт версии'));
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-09-05" manualRequests={[]} busy={false} onDispatch={vi.fn()} />);
    const trigger = await screen.findByRole('button', { name: 'Действия компании Балтика' });
    trigger.focus();
    await user.keyboard('{ArrowDown}{End}{Enter}');
    const dialog = within(screen.getByRole('dialog', { name: 'Изменить наёмную компанию' }));
    await user.clear(dialog.getByLabelText('Название компании'));
    await user.type(dialog.getByLabelText('Название компании'), 'Север');
    contractorApi.companies.mockResolvedValue([{ ...company, version: 5, name: 'Сохранённое название' }]);
    await user.click(dialog.getByRole('button', { name: 'Сохранить компанию' }));
    expect(await dialog.findByRole('alert')).toHaveTextContent('Закройте форму');
    expect(contractorApi.updateCompany).toHaveBeenCalledWith('token', 'external-warehouse', expect.objectContaining({ version: 4 }), expect.objectContaining({ name: 'Север' }));
    await user.click(dialog.getByRole('button', { name: 'Отмена' }));
    await user.click(screen.getByRole('button', { name: 'Действия компании Сохранённое название' }));
    await user.click(screen.getByRole('menuitem', { name: 'Изменить компанию' }));
    expect(screen.getByLabelText('Название компании')).toHaveValue('Сохранённое название');
  });

  it('detaches a driver explicitly and keeps company deletion unavailable while it has drivers', async () => {
    const user = userEvent.setup();
    contractorApi.companies.mockResolvedValue([company]);
    contractorApi.list.mockResolvedValue([{ workerId: 'member', version: 8, displayName: 'Петров', phone: '123', active: true, companyId: company.companyId }]);
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-09-05" manualRequests={[]} busy={false} onDispatch={vi.fn()} />);
    await user.click(await screen.findByRole('button', { name: 'Действия компании Балтика' }));
    await user.click(screen.getByRole('menuitem', { name: 'Изменить компанию' }));
    expect(screen.getByRole('button', { name: 'Удалить' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Отмена' }));
    await user.click(screen.getByRole('button', { name: 'Изменить' }));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Компания' }), '');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    await waitFor(() => expect(contractorApi.update).toHaveBeenCalledWith('token', 'external-warehouse', expect.objectContaining({ version: 8 }), expect.objectContaining({ companyId: null })));
  });

  it('shows a catalog load failure with retry and never hides it behind an empty successful state', async () => {
    const user = userEvent.setup();
    contractorApi.companies.mockRejectedValueOnce(new Error('Каталог компаний недоступен'));
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-09-05" manualRequests={[]} busy={false} onDispatch={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Каталог компаний недоступен');
    expect(screen.queryByText('Наёмные водители не добавлены')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Обновить каталог' }));
    expect(await screen.findByText('Иван Петров')).toBeVisible();
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
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-08-31" manualRequests={[]} busy={false} onDispatch={() => Promise.resolve(dispatchResult())} />);

    await user.click(await screen.findByRole('button', { name: 'Добавить водителя' }));
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
    const onDispatch = vi.fn(() => Promise.resolve(dispatchResult()));
    const request = requestFixture({
      id: 'request-manual',
      warehouse_id: 'local-warehouse',
      scheduled_date: '2026-08-31',
      status: 'UNASSIGNED',
      name: '№31',
    });
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-08-31" manualRequests={[request]} busy={false} onDispatch={onDispatch} />);

    expect(await screen.findByText(/31 августа 2026/)).toBeVisible();
    await user.click((await screen.findAllByRole('button', { name: 'Назначить на рейс' }))[0]!);
    await waitFor(() => expect(onDispatch).toHaveBeenCalledWith('contractor-active', 'AUTO', []));

    await user.click(screen.getAllByRole('button', { name: 'Выстроить вручную' })[0]!);
    const dialog = await screen.findByRole('dialog', { name: 'Назначить на рейс вручную' });
    await user.click(dialog.querySelector('input[type="checkbox"]')!);
    await user.click(screen.getByRole('button', { name: 'Передать выбранные (1)' }));
    await waitFor(() => expect(onDispatch).toHaveBeenCalledWith('contractor-active', 'MANUAL', ['request-manual']));
  });

  it('does not offer a real RWMS pickup when it is the only unassigned request', async () => {
    const onDispatch = vi.fn(() => Promise.resolve(dispatchResult()));
    const pickup = requestFixture({
      id: 'rwms-pickup-only',
      source_system: 'RWMS',
      type: 'PICKUP',
      name: 'Вывоз RWMS №17',
      scheduled_date: '2026-08-31',
      tasks: [{
        ...requestFixture().tasks![0]!,
        id: 'rwms-pickup-only-task',
        request_id: 'rwms-pickup-only',
        type: 'PICKUP',
      }],
    });
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-08-31" manualRequests={[pickup]} busy={false} onDispatch={onDispatch} />);

    expect(await screen.findByText(/Вывоз RWMS пока нельзя передать наёмному водителю/)).toBeVisible();
    expect((await screen.findAllByRole('button', { name: 'Назначить на рейс' })).every((button) => button.hasAttribute('disabled'))).toBe(true);
    expect(screen.getAllByRole('button', { name: 'Выстроить вручную' }).every((button) => button.hasAttribute('disabled'))).toBe(true);
    expect(screen.queryByText('Вывоз RWMS №17')).not.toBeInTheDocument();
    expect(onDispatch).not.toHaveBeenCalled();
  });

  it('keeps supported delivery selectable while hiding a real RWMS pickup', async () => {
    const user = userEvent.setup();
    const onDispatch = vi.fn(() => Promise.resolve(dispatchResult()));
    const delivery = requestFixture({
      id: 'rwms-delivery-supported',
      source_system: 'RWMS',
      type: 'DELIVERY',
      name: 'Доставка RWMS №18',
      scheduled_date: '2026-08-31',
    });
    const pickup = requestFixture({
      id: 'rwms-pickup-unsupported',
      source_system: 'RWMS',
      type: 'PICKUP',
      name: 'Вывоз RWMS №19',
      scheduled_date: '2026-08-31',
      tasks: [{
        ...requestFixture().tasks![0]!,
        id: 'rwms-pickup-unsupported-task',
        request_id: 'rwms-pickup-unsupported',
        type: 'PICKUP',
      }],
    });
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-08-31" manualRequests={[delivery, pickup]} busy={false} onDispatch={onDispatch} />);

    expect(await screen.findByText(/для него нужен внутренний маршрут/)).toBeVisible();
    const autoButton = (await screen.findAllByRole('button', { name: 'Назначить на рейс' }))[0]!;
    expect(autoButton).toBeEnabled();
    await user.click(autoButton);
    await waitFor(() => expect(onDispatch).toHaveBeenCalledWith('contractor-active', 'AUTO', []));

    await user.click(screen.getAllByRole('button', { name: 'Выстроить вручную' })[0]!);
    const dialog = await screen.findByRole('dialog', { name: 'Назначить на рейс вручную' });
    expect(dialog).toHaveTextContent('Доставка RWMS №18');
    expect(dialog).not.toHaveTextContent('Вывоз RWMS №19');
    await user.click(dialog.querySelector('input[type="checkbox"]')!);
    await user.click(screen.getByRole('button', { name: 'Передать выбранные (1)' }));
    await waitFor(() => expect(onDispatch).toHaveBeenCalledWith(
      'contractor-active',
      'MANUAL',
      ['rwms-delivery-supported'],
    ));
  });

  it('deletes a contractor only from the edit dialog after confirmation', async () => {
    const user = userEvent.setup();
    contractorApi.remove.mockResolvedValue(undefined);
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-08-31" manualRequests={[]} busy={false} onDispatch={() => Promise.resolve(dispatchResult())} />);

    await user.click((await screen.findAllByRole('button', { name: 'Изменить' }))[0]!);
    await user.click(screen.getByRole('button', { name: 'Удалить' }));
    await user.click(screen.getByRole('button', { name: 'Удалить водителя' }));
    await waitFor(() => expect(contractorApi.remove).toHaveBeenCalledWith(
      'token',
      'external-warehouse',
      expect.objectContaining({ workerId: 'contractor-active' }),
    ));
  });

  it('creates and copies one stable canonical route share only after an explicit click', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-01T09:00:00.000Z'));
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const onDispatch = vi.fn(() => Promise.resolve(dispatchResult()));
    const request = requestFixture({
      id: 'request-manual',
      warehouse_id: 'local-warehouse',
      scheduled_date: '2026-08-31',
      status: 'UNASSIGNED',
    });
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-08-31" manualRequests={[request]} busy={false} onDispatch={onDispatch} />);

    await user.click((await screen.findAllByRole('button', { name: 'Назначить на рейс' }))[0]!);
    expect(await screen.findByText('Заданий: 2')).toBeVisible();
    expect(contractorApi.share).not.toHaveBeenCalled();

    const copyButton = screen.getByRole('button', { name: 'Скопировать маршрут' });
    await user.click(copyButton);
    await waitFor(() => expect(contractorApi.share).toHaveBeenCalledWith(
      'token',
      'external-warehouse',
      {
        contractorWorkerId: 'contractor-active',
        expiresAt: '2026-09-02T00:00:00.000Z',
        externalTaskIds: ['external-task-2', 'external-task-1'],
      },
      'handoff-command',
    ));
    expect(writeText).toHaveBeenCalledWith(new URL('/contractor-routes/signed-token', window.location.origin).toString());
    expect(await screen.findByText('Ссылка на маршрут скопирована.')).toBeVisible();

    await user.click(copyButton);
    await waitFor(() => expect(contractorApi.share).toHaveBeenCalledTimes(2));
    expect(contractorApi.share.mock.calls[1]).toEqual(contractorApi.share.mock.calls[0]);
  });

  it('shows the same canonical route block after manual dispatch', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-01T09:00:00.000Z'));
    const user = userEvent.setup();
    const onDispatch = vi.fn(() => Promise.resolve(dispatchResult({
      mode: 'MANUAL',
      contractor_handoff_command_id: 'manual-command',
      external_task_ids: ['manual-task'],
    })));
    const request = requestFixture({
      id: 'request-manual',
      warehouse_id: 'local-warehouse',
      scheduled_date: '2026-08-31',
      status: 'UNASSIGNED',
    });
    render(<ContractorDriversPanel warehouseId="external-warehouse" planningDate="2026-08-31" manualRequests={[request]} busy={false} onDispatch={onDispatch} />);

    await user.click((await screen.findAllByRole('button', { name: 'Выстроить вручную' }))[0]!);
    const dialog = await screen.findByRole('dialog', { name: 'Назначить на рейс вручную' });
    await user.click(dialog.querySelector('input[type="checkbox"]')!);
    await user.click(screen.getByRole('button', { name: 'Передать выбранные (1)' }));

    expect(await screen.findByText('Заданий: 1')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Скопировать маршрут' })).toBeEnabled();
    expect(contractorApi.share).not.toHaveBeenCalled();
  });

  it('reconstructs a canonical route after reload and ignores generated-only assignments', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-01T09:00:00.000Z'));
    const canonical = requestFixture({
      id: 'canonical-request',
      scheduled_date: '2026-08-31',
      assignment_type: 'CONTRACTOR_HANDOFF',
      assigned_contractor_worker_id: 'contractor-active',
      assigned_contractor_name: 'Иван Петров',
      contractor_handoff_command_id: 'persisted-command',
      contractor_handoff_sequence: 0,
      external_task_ids: ['persisted-task-1', 'persisted-task-2'],
    });
    const generated = requestFixture({
      id: 'generated-request',
      source_system: 'GENERATED',
      scheduled_date: '2026-08-31',
      assignment_type: 'CONTRACTOR_HANDOFF',
      assigned_contractor_worker_id: 'contractor-active',
      assigned_contractor_name: 'Иван Петров',
      contractor_handoff_command_id: null,
      contractor_handoff_sequence: null,
      external_task_ids: [],
    });
    const view = render(<ContractorDriversPanel
      warehouseId="external-warehouse"
      planningDate="2026-08-31"
      manualRequests={[]}
      routeRequests={[canonical, generated]}
      busy={false}
      onDispatch={() => Promise.resolve(dispatchResult())}
    />);

    expect(await screen.findByText('Заданий: 2')).toBeVisible();
    expect(screen.getAllByRole('button', { name: 'Скопировать маршрут' })).toHaveLength(1);
    expect(contractorApi.share).not.toHaveBeenCalled();

    view.rerender(<ContractorDriversPanel
      warehouseId="external-warehouse"
      planningDate="2026-08-31"
      manualRequests={[]}
      routeRequests={[generated]}
      busy={false}
      onDispatch={() => Promise.resolve(dispatchResult())}
    />);
    expect(screen.queryByRole('button', { name: 'Скопировать маршрут' })).not.toBeInTheDocument();
  });

  it('restores exact task order from the persisted handoff sequence, not response row order', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-01T09:00:00.000Z'));
    const user = userEvent.setup();
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
    });
    const sequenceOne = requestFixture({
      id: 'sequence-one-request',
      scheduled_date: '2026-08-31',
      assignment_type: 'CONTRACTOR_HANDOFF',
      assigned_contractor_worker_id: 'contractor-active',
      assigned_contractor_name: 'Иван Петров',
      contractor_handoff_command_id: 'ordered-command',
      contractor_handoff_sequence: 1,
      external_task_ids: ['ordered-task-2'],
    });
    const sequenceZero = requestFixture({
      id: 'sequence-zero-request',
      scheduled_date: '2026-08-31',
      assignment_type: 'CONTRACTOR_HANDOFF',
      assigned_contractor_worker_id: 'contractor-active',
      assigned_contractor_name: 'Иван Петров',
      contractor_handoff_command_id: 'ordered-command',
      contractor_handoff_sequence: 0,
      external_task_ids: ['ordered-task-1'],
    });
    render(<ContractorDriversPanel
      warehouseId="external-warehouse"
      planningDate="2026-08-31"
      manualRequests={[]}
      routeRequests={[sequenceOne, sequenceZero]}
      busy={false}
      onDispatch={() => Promise.resolve(dispatchResult())}
    />);

    await user.click(await screen.findByRole('button', { name: 'Скопировать маршрут' }));
    await waitFor(() => expect(contractorApi.share).toHaveBeenCalledWith(
      'token',
      'external-warehouse',
      expect.objectContaining({ externalTaskIds: ['ordered-task-1', 'ordered-task-2'] }),
      'ordered-command',
    ));
  });

  it.each([
    {
      caseName: 'missing sequence',
      projectionComplete: true,
      requests: [requestFixture({
        id: 'missing-sequence',
        scheduled_date: '2026-08-31',
        assignment_type: 'CONTRACTOR_HANDOFF',
        assigned_contractor_worker_id: 'contractor-active',
        assigned_contractor_name: 'Иван Петров',
        contractor_handoff_command_id: 'not-ready-missing',
        contractor_handoff_sequence: null,
        external_task_ids: ['missing-sequence-task'],
      })],
    },
    {
      caseName: 'duplicate sequence',
      projectionComplete: true,
      requests: [
        requestFixture({
          id: 'duplicate-sequence-a', scheduled_date: '2026-08-31', assignment_type: 'CONTRACTOR_HANDOFF',
          assigned_contractor_worker_id: 'contractor-active', contractor_handoff_command_id: 'not-ready-duplicate-sequence',
          contractor_handoff_sequence: 0, external_task_ids: ['duplicate-sequence-task-a'],
        }),
        requestFixture({
          id: 'duplicate-sequence-b', scheduled_date: '2026-08-31', assignment_type: 'CONTRACTOR_HANDOFF',
          assigned_contractor_worker_id: 'contractor-active', contractor_handoff_command_id: 'not-ready-duplicate-sequence',
          contractor_handoff_sequence: 0, external_task_ids: ['duplicate-sequence-task-b'],
        }),
      ],
    },
    {
      caseName: 'gapped sequence',
      projectionComplete: true,
      requests: [
        requestFixture({
          id: 'gapped-sequence-a', scheduled_date: '2026-08-31', assignment_type: 'CONTRACTOR_HANDOFF',
          assigned_contractor_worker_id: 'contractor-active', contractor_handoff_command_id: 'not-ready-gap',
          contractor_handoff_sequence: 0, external_task_ids: ['gapped-sequence-task-a'],
        }),
        requestFixture({
          id: 'gapped-sequence-b', scheduled_date: '2026-08-31', assignment_type: 'CONTRACTOR_HANDOFF',
          assigned_contractor_worker_id: 'contractor-active', contractor_handoff_command_id: 'not-ready-gap',
          contractor_handoff_sequence: 2, external_task_ids: ['gapped-sequence-task-b'],
        }),
      ],
    },
    {
      caseName: 'mixed contractor identities',
      projectionComplete: true,
      requests: [
        requestFixture({
          id: 'mixed-contractor-a', scheduled_date: '2026-08-31', assignment_type: 'CONTRACTOR_HANDOFF',
          assigned_contractor_worker_id: 'contractor-active', contractor_handoff_command_id: 'not-ready-mixed',
          contractor_handoff_sequence: 0, external_task_ids: ['mixed-task-a'],
        }),
        requestFixture({
          id: 'mixed-contractor-b', scheduled_date: '2026-08-31', assignment_type: 'CONTRACTOR_HANDOFF',
          assigned_contractor_worker_id: 'contractor-late', contractor_handoff_command_id: 'not-ready-mixed',
          contractor_handoff_sequence: 1, external_task_ids: ['mixed-task-b'],
        }),
      ],
    },
    {
      caseName: 'duplicate task identities',
      projectionComplete: true,
      requests: [
        requestFixture({
          id: 'duplicate-task-a', scheduled_date: '2026-08-31', assignment_type: 'CONTRACTOR_HANDOFF',
          assigned_contractor_worker_id: 'contractor-active', contractor_handoff_command_id: 'not-ready-duplicate-task',
          contractor_handoff_sequence: 0, external_task_ids: ['same-task'],
        }),
        requestFixture({
          id: 'duplicate-task-b', scheduled_date: '2026-08-31', assignment_type: 'CONTRACTOR_HANDOFF',
          assigned_contractor_worker_id: 'contractor-active', contractor_handoff_command_id: 'not-ready-duplicate-task',
          contractor_handoff_sequence: 1, external_task_ids: ['same-task'],
        }),
      ],
    },
    {
      caseName: 'more task identities than one share accepts',
      projectionComplete: true,
      requests: [requestFixture({
        id: 'too-many-tasks',
        scheduled_date: '2026-08-31',
        assignment_type: 'CONTRACTOR_HANDOFF',
        assigned_contractor_worker_id: 'contractor-active',
        contractor_handoff_command_id: 'not-ready-too-many-tasks',
        contractor_handoff_sequence: 0,
        external_task_ids: Array.from({ length: 51 }, (_, index) => `too-many-task-${index}`),
      })],
    },
    {
      caseName: 'incomplete workspace projection',
      projectionComplete: false,
      requests: [requestFixture({
        id: 'incomplete-projection',
        scheduled_date: '2026-08-31',
        assignment_type: 'CONTRACTOR_HANDOFF',
        assigned_contractor_worker_id: 'contractor-active',
        contractor_handoff_command_id: 'not-ready-projection',
        contractor_handoff_sequence: 0,
        external_task_ids: ['incomplete-projection-task'],
      })],
    },
  ])('blocks route sharing for $caseName', async ({ requests, projectionComplete }) => {
    render(<ContractorDriversPanel
      warehouseId="external-warehouse"
      planningDate="2026-08-31"
      manualRequests={[]}
      routeRequests={requests}
      routeRequestsComplete={projectionComplete}
      busy={false}
      onDispatch={() => Promise.resolve(dispatchResult())}
    />);

    expect(await screen.findByText('Маршрут ещё не готов для отправки.')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Скопировать маршрут' })).toBeDisabled();
    expect(contractorApi.share).not.toHaveBeenCalled();
  });

  it('shows a selectable URL when clipboard access fails and a Russian error when share creation fails', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-01T09:00:00.000Z'));
    const user = userEvent.setup();
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
    });
    const canonical = requestFixture({
      id: 'canonical-request',
      scheduled_date: '2026-08-31',
      assignment_type: 'CONTRACTOR_HANDOFF',
      assigned_contractor_worker_id: 'contractor-active',
      assigned_contractor_name: 'Иван Петров',
      contractor_handoff_command_id: 'persisted-command',
      contractor_handoff_sequence: 0,
      external_task_ids: ['persisted-task-1'],
    });
    render(<ContractorDriversPanel
      warehouseId="external-warehouse"
      planningDate="2026-08-31"
      manualRequests={[]}
      routeRequests={[canonical]}
      busy={false}
      onDispatch={() => Promise.resolve(dispatchResult())}
    />);

    const copyButton = await screen.findByRole('button', { name: 'Скопировать маршрут' });
    await user.click(copyButton);
    expect(await screen.findByText('Автокопирование недоступно. Скопируйте ссылку вручную.')).toBeVisible();
    expect(screen.getByLabelText('Ссылка на маршрут для ручного копирования')).toHaveValue(
      new URL('/contractor-routes/signed-token', window.location.origin).toString(),
    );

    contractorApi.share.mockRejectedValueOnce(new Error('Не удалось создать ссылку. Повторите действие.'));
    await user.click(copyButton);
    expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось создать ссылку');
  });

  it('disables sharing when the deterministic expiry falls outside the server window', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-01T09:00:00.000Z'));
    const canonical = requestFixture({
      scheduled_date: '2026-10-15',
      assignment_type: 'CONTRACTOR_HANDOFF',
      assigned_contractor_worker_id: 'contractor-active',
      assigned_contractor_name: 'Иван Петров',
      contractor_handoff_command_id: 'future-command',
      contractor_handoff_sequence: 0,
      external_task_ids: ['future-task'],
    });
    render(<ContractorDriversPanel
      warehouseId="external-warehouse"
      planningDate="2026-10-15"
      manualRequests={[]}
      routeRequests={[canonical]}
      busy={false}
      onDispatch={() => Promise.resolve(dispatchResult({ planning_date: '2026-10-15' }))}
    />);

    expect(await screen.findByRole('button', { name: 'Скопировать маршрут' })).toBeDisabled();
    expect(screen.getByText(/в пределах 30 дней/)).toBeVisible();
    expect(contractorApi.share).not.toHaveBeenCalled();
  });
});
