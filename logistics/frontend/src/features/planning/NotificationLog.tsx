import { BellRing, CheckCircle2 } from 'lucide-react';
import type { PlanNotificationLog } from '../../domain/types';

/** Bottom-left journal of simulated contact messages created at plan confirmation. */
export function NotificationLog({ logs, sidebarsCollapsed }: {
  logs: PlanNotificationLog[];
  sidebarsCollapsed: boolean;
}) {
  if (!logs.length) return null;
  return (
    <aside className={`plan-notification-log ${sidebarsCollapsed ? 'plan-notification-log--collapsed' : ''}`} aria-label="Журнал тестовых уведомлений" aria-live="polite">
      <header><BellRing size={15} /><strong>Уведомления · тест</strong><span>{logs.length}</span></header>
      <div>
        {logs.map((log) => (
          <details key={log.id} open={logs.length === 1}>
            <summary>
              <CheckCircle2 size={13} />
              <span><strong>{log.recipient_name || 'Контактное лицо'}</strong><small>{log.recipient_contact || 'контакт не указан'} · получено в журнале</small></span>
            </summary>
            <p>{log.message}</p>
            {log.includes_driver_passport ? <small className="plan-notification-log__sensitive">В сообщение включены паспортные данные водителя.</small> : null}
          </details>
        ))}
      </div>
    </aside>
  );
}
