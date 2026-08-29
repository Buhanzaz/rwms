import { format } from 'date-fns';
import { ru } from 'date-fns/locale';
import { CalendarDays } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { DayPicker } from 'react-day-picker';
import 'react-day-picker/style.css';

function parseIsoDate(value: string): Date | undefined {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return undefined;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 12);
  return Number.isNaN(date.getTime()) ? undefined : date;
}

function formatIsoDate(value: Date): string {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, '0');
  const day = String(value.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/** Controlled Russian-language calendar that preserves an ISO date without timezone drift. */
export function DatePicker({ value, onChange, label, disabled = false, className = '' }: {
  value: string;
  onChange: (value: string) => void;
  label: string;
  disabled?: boolean;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const selected = useMemo(() => parseIsoDate(value), [value]);

  useEffect(() => {
    if (!open) return undefined;
    const closeOnPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('pointerdown', closeOnPointerDown);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOnPointerDown);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  return (
    <div className={`date-picker ${className}`} ref={rootRef}>
      <button
        type="button"
        className="date-picker__trigger"
        aria-label={label}
        aria-expanded={open}
        aria-haspopup="dialog"
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
      >
        <CalendarDays size={15} aria-hidden="true" />
        <span>{selected ? format(selected, 'd MMMM yyyy', { locale: ru }) : 'Выберите дату'}</span>
      </button>
      {open ? (
        <div className="date-picker__popover" role="dialog" aria-label={`Календарь: ${label}`}>
          <DayPicker
            mode="single"
            locale={ru}
            {...(selected ? { selected, defaultMonth: selected } : {})}
            showOutsideDays
            onSelect={(date) => {
              if (!date) return;
              onChange(formatIsoDate(date));
              setOpen(false);
            }}
          />
        </div>
      ) : null}
    </div>
  );
}
