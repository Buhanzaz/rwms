import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useUiStore } from '../src/stores/ui-store';

describe('notification history', () => {
  beforeEach(() => {
    window.localStorage.clear();
    useUiStore.setState({ notifications: [], notificationDurationSeconds: 8 });
  });

  afterEach(() => {
    useUiStore.setState({ notifications: [], notificationDurationSeconds: 8 });
  });

  it('keeps only the latest message for one replacement key and retains it after the toast is hidden', () => {
    const { toast } = useUiStore.getState();

    toast({
      tone: 'success',
      replacementKey: 'automatic-plan-result',
      title: 'План готов: 3 рейса',
    });
    toast({ tone: 'info', title: 'Независимое уведомление' });
    toast({
      tone: 'success',
      replacementKey: 'automatic-plan-result',
      title: 'План готов: 4 рейса',
    });

    const notifications = useUiStore.getState().notifications;
    expect(notifications.map((message) => message.title)).toEqual([
      'Независимое уведомление',
      'План готов: 4 рейса',
    ]);
    useUiStore.getState().dismissToast(notifications[1]!.id);
    expect(useUiStore.getState().notifications[1]).toMatchObject({ visible: false, read: false });
  });

  it('marks history as read, clears it and persists a bounded display duration', () => {
    useUiStore.getState().toast({ tone: 'info', title: 'Склад обновлён' });
    useUiStore.getState().markNotificationsRead();
    expect(useUiStore.getState().notifications[0]?.read).toBe(true);

    useUiStore.getState().setNotificationDurationSeconds(120);
    expect(useUiStore.getState().notificationDurationSeconds).toBe(60);
    expect(window.localStorage.getItem('rwms-logistics-notification-duration-seconds')).toBe('60');

    useUiStore.getState().clearNotifications();
    expect(useUiStore.getState().notifications).toEqual([]);
  });

  it('replaces an actionable notification in memory without duplicating its history row', () => {
    const firstAction = vi.fn();
    const latestAction = vi.fn();
    useUiStore.getState().toast({
      tone: 'info',
      replacementKey: 'representative-request-request-1',
      title: 'Новая заявка',
      action: { label: 'Открыть заявку', onActivate: firstAction },
    });
    useUiStore.getState().toast({
      tone: 'info',
      replacementKey: 'representative-request-request-1',
      title: 'Заявка обновлена',
      action: { label: 'Открыть заявку', onActivate: latestAction },
    });

    const notifications = useUiStore.getState().notifications;
    expect(notifications).toHaveLength(1);
    notifications[0]?.action?.onActivate();
    expect(latestAction).toHaveBeenCalledOnce();
    expect(firstAction).not.toHaveBeenCalled();
  });
});
