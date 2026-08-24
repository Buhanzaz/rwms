import { X } from 'lucide-react';
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from 'react';
import { useEffect } from 'react';
import { useUiStore } from '../stores/ui-store';

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
  const detail = error instanceof Error ? error.message : 'Неизвестная ошибка';
  return (
    <div className="error-panel" role="alert" data-testid="error-panel">
      <strong>{title}</strong>
      <p>{detail}</p>
      {onRetry ? <Button size="sm" onClick={onRetry}>Повторить</Button> : null}
    </div>
  );
}

export function Toasts() {
  const toasts = useUiStore((state) => state.toasts);
  const dismiss = useUiStore((state) => state.dismissToast);
  return (
    <div className="toasts" aria-live="polite" aria-label="Уведомления">
      {toasts.map((toast) => (
        <div className={`toast toast--${toast.tone}`} key={toast.id} role={toast.tone === 'error' ? 'alert' : 'status'}>
          <div><strong>{toast.title}</strong>{toast.detail ? <p>{toast.detail}</p> : null}</div>
          <button onClick={() => dismiss(toast.id)} aria-label="Закрыть уведомление"><X size={15} /></button>
        </div>
      ))}
    </div>
  );
}

export function Spinner({ label = 'Загрузка' }: { label?: string }) {
  return <span className="spinner" role="status"><i />{label}</span>;
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'accent' }) {
  return <span className={`badge badge--${tone}`}>{children}</span>;
}
