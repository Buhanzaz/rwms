import { Bell, Moon, Sun, Trash2, X } from 'lucide-react';
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from 'react';
import { useEffect, useState } from 'react';
import { useUiStore } from '../stores/ui-store';
import { userFacingErrorDetail } from '../utils/user-facing-error';
import type { NotificationMessage } from '../stores/ui-store';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'sm' | 'md';
}

export function Button({ variant = 'secondary', size = 'md', className = '', ...props }: ButtonProps) {
  return <button className={`button button--${variant} button--${size} ${className}`} {...props} />;
}

interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string | undefined;
  hint?: string | undefined;
}

export function Field({ label, error, hint, className = '', ...props }: FieldProps) {
  return (
    <label className={`field ${className}`}>
      <span className="field__label">{label}</span>
      <input className={`input ${error ? 'input--error' : ''}`} aria-label={props['aria-label'] ?? label} {...props} />
      {error ? <span className="field__error">{error}</span> : hint ? <span className="field__hint">{hint}</span> : null}
    </label>
  );
}

interface SelectFieldProps extends SelectHTMLAttributes<HTMLSelectElement> {
  label: string;
  error?: string | undefined;
  children: ReactNode;
}

export function SelectField({ label, error, children, className = '', ...props }: SelectFieldProps) {
  return (
    <label className={`field ${className}`}>
      <span className="field__label">{label}</span>
      <select className={`input ${error ? 'input--error' : ''}`} aria-label={props['aria-label'] ?? label} {...props}>
        {children}
      </select>
      {error ? <span className="field__error">{error}</span> : null}
    </label>
  );
}

export function CheckboxField({ label, checked, onChange, disabled = false }: {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <label className="checkbox-field">
      <input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} disabled={disabled} />
      <span>{label}</span>
    </label>
  );
}

/** Accessible binary switch used for immediate settings such as theme and overtime. */
export function Switch({ checked, onCheckedChange, disabled = false, label }: {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  disabled?: boolean;
  label: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      className="switch"
      disabled={disabled}
      onClick={() => onCheckedChange(!checked)}
    >
      <span className="switch__thumb" aria-hidden="true" />
    </button>
  );
}

export function SwitchField({ label, description, checked, onCheckedChange, disabled = false }: {
  label: string;
  description?: string;
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <div className={`switch-field ${disabled ? 'switch-field--disabled' : ''}`}>
      <span><strong>{label}</strong>{description ? <small>{description}</small> : null}</span>
      <Switch checked={checked} onCheckedChange={onCheckedChange} disabled={disabled} label={label} />
    </div>
  );
}

/** Persistent single-button light/dark theme toggle shown in the logistics header. */
export function ThemeSwitch() {
  const theme = useUiStore((state) => state.theme);
  const setTheme = useUiStore((state) => state.setTheme);
  const dark = theme === 'dark';
  return (
    <Button
      className="theme-switch"
      aria-label={dark ? 'Включить светлую тему' : 'Включить тёмную тему'}
      aria-pressed={dark}
      title={dark ? 'Включить светлую тему' : 'Включить тёмную тему'}
      onClick={() => setTheme(dark ? 'light' : 'dark')}
    >
      {dark ? <Moon size={17} aria-hidden="true" /> : <Sun size={17} aria-hidden="true" />}
    </Button>
  );
}

export function Modal({ title, description, onClose, children, footer, wide = false }: {
  title: string;
  description?: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
}) {
  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', listener);
    return () => window.removeEventListener('keydown', listener);
  }, [onClose]);
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className={`modal ${wide ? 'modal--wide' : ''}`} role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal__header">
          <div>
            <h2>{title}</h2>
            {description ? <p>{description}</p> : null}
          </div>
          <Button variant="ghost" size="sm" onClick={onClose} aria-label="Закрыть окно"><X size={18} /></Button>
        </header>
        <div className="modal__body">{children}</div>
        {footer ? <footer className="modal__footer">{footer}</footer> : null}
      </section>
    </div>
  );
}

export function EmptyState({ icon, title, description, action }: {
  icon?: ReactNode;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state">
      {icon ? <div className="empty-state__icon">{icon}</div> : null}
      <strong>{title}</strong>
      <p>{description}</p>
      {action}
    </div>
  );
}

export function ErrorPanel({ title = 'Не удалось загрузить данные', error, onRetry }: {
  title?: string;
  error: unknown;
  onRetry?: () => void;
}) {
  const detail = userFacingErrorDetail(error);
  return (
    <div className="error-panel" role="alert" data-testid="error-panel">
      <strong>{title}</strong>
      <p>{detail}</p>
      {onRetry ? <Button size="sm" onClick={onRetry}>Повторить</Button> : null}
    </div>
  );
}

export function Toasts() {
  const notifications = useUiStore((state) => state.notifications);
  const durationSeconds = useUiStore((state) => state.notificationDurationSeconds);
  const dismiss = useUiStore((state) => state.dismissToast);
  return (
    <div className="toasts" aria-live="polite" aria-label="Уведомления">
      {notifications.filter((notification) => notification.visible).slice(-4).map((notification) => (
        <ToastItem key={notification.id} notification={notification} durationSeconds={durationSeconds} onDismiss={dismiss} />
      ))}
    </div>
  );
}

function ToastItem({ notification, durationSeconds, onDismiss }: {
  notification: NotificationMessage;
  durationSeconds: number;
  onDismiss: (id: string) => void;
}) {
  useEffect(() => {
    const timeout = window.setTimeout(() => onDismiss(notification.id), durationSeconds * 1000);
    return () => window.clearTimeout(timeout);
  }, [durationSeconds, notification.createdAt, notification.id, onDismiss]);
  return (
    <div className={`toast toast--${notification.tone}`} role={notification.tone === 'error' ? 'alert' : 'status'}>
      <div><strong>{notification.title}</strong>{notification.detail ? <p>{notification.detail}</p> : null}</div>
      <button onClick={() => onDismiss(notification.id)} aria-label="Скрыть уведомление"><X size={15} /></button>
    </div>
  );
}

export function NotificationCenter() {
  const [open, setOpen] = useState(false);
  const notifications = useUiStore((state) => state.notifications);
  const durationSeconds = useUiStore((state) => state.notificationDurationSeconds);
  const setDurationSeconds = useUiStore((state) => state.setNotificationDurationSeconds);
  const markRead = useUiStore((state) => state.markNotificationsRead);
  const clear = useUiStore((state) => state.clearNotifications);
  const unread = notifications.filter((notification) => !notification.read).length;
  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next) markRead();
  };
  const activate = (notification: NotificationMessage) => {
    notification.action?.onActivate();
    setOpen(false);
  };
  useEffect(() => {
    if (!open) return undefined;
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    window.addEventListener('keydown', close);
    return () => window.removeEventListener('keydown', close);
  }, [open]);
  return (
    <div className="notification-center">
      <Button size="sm" onClick={toggle} aria-label={`Уведомления${unread ? `: ${unread} новых` : ''}`} aria-expanded={open}>
        <Bell size={17} />
        {unread ? <span className="notification-center__badge">{unread > 99 ? '99+' : unread}</span> : null}
      </Button>
      {open ? (
        <section className="notification-center__panel" aria-label="История уведомлений">
          <header><strong>Уведомления</strong><small>{notifications.length}</small></header>
          <div className="notification-center__preferences"><Field label="Показывать уведомление, секунд" type="number" min="1" max="60" value={durationSeconds} onChange={(event) => setDurationSeconds(Number(event.target.value))} hint="После этого уведомление остаётся в истории" /></div>
          <div className="notification-center__list">
            {[...notifications].reverse().map((notification) => (
              <article className={`notification-history notification-history--${notification.tone}`} key={notification.id}>
                <strong>{notification.title}</strong>
                {notification.detail ? <p>{notification.detail}</p> : null}
                <time>{new Intl.DateTimeFormat('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(notification.createdAt))}</time>
                {notification.action ? (
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    aria-label={`${notification.action.label}: ${notification.title}`}
                    onClick={() => activate(notification)}
                  >{notification.action.label}</Button>
                ) : null}
              </article>
            ))}
            {!notifications.length ? <p className="notification-center__empty">История пуста</p> : null}
          </div>
          <Button size="sm" variant="ghost" disabled={!notifications.length} onClick={clear}><Trash2 size={14} />Очистить всё</Button>
        </section>
      ) : null}
    </div>
  );
}

export function Spinner({ label = 'Загрузка' }: { label?: string }) {
  return <span className="spinner" role="status"><i />{label}</span>;
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'accent' }) {
  return <span className={`badge badge--${tone}`}>{children}</span>;
}
