import { afterEach, beforeEach, describe, expect, it } from 'vitest';
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
});
