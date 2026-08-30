import { format } from 'date-fns';
import { ru } from 'date-fns/locale';
import { CalendarDays } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { DayPicker, type DateRange } from 'react-day-picker';
import 'react-day-picker/style.css';
import { formatIsoDate, parseIsoDate } from './date-value';
import { Button } from './ui';

/** Controlled hotel-style inclusive date-range calendar without timezone drift. */
export function DateRangePicker({ from, to, onChange, label, disabled = false }: {
  from: string;
  to: string;
  onChange: (from: string, to: string) => void;
  label: string;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const committed = useMemo<DateRange | undefined>(() => {
    const fromDate = parseIsoDate(from);
    const toDate = parseIsoDate(to);
    return fromDate ? { from: fromDate, ...(toDate ? { to: toDate } : {}) } : undefined;
  }, [from, to]);
  const [pending, setPending] = useState<DateRange | undefined>(committed);
  useEffect(() => setPending(committed), [committed]);
  useEffect(() => {
    if (!open) return undefined;
    const close = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', close);
    return () => document.removeEventListener('pointerdown', close);
  }, [open]);
  const formatRange = () => {
    if (!committed?.from) return 'Выберите период';
    if (!committed.to) return format(committed.from, 'd MMMM yyyy', { locale: ru });
    return `${format(committed.from, 'd MMM', { locale: ru })} — ${format(committed.to, 'd MMM yyyy', { locale: ru })}`;
  };
  return <div className="date-picker span-2" ref={rootRef}>
    <button type="button" className="date-picker__trigger" aria-label={label} aria-expanded={open} aria-haspopup="dialog" disabled={disabled} onClick={() => setOpen((current) => !current)}>
      <CalendarDays size={15} aria-hidden="true" />
      <span>{formatRange()}</span>
    </button>
    {open ? <div className="date-picker__popover date-picker__popover--range" role="dialog" aria-label={`Календарь: ${label}`}>
      <DayPicker mode="range" locale={ru} {...(pending ? { selected: pending } : {})} {...(pending?.from ? { defaultMonth: pending.from } : {})} showOutsideDays onSelect={(range) => {
        if (!range?.from) return;
        if (!pending?.from || pending.to) {
          setPending({ from: range.from });
          return;
        }
        setPending(range);
        if (range.to) onChange(formatIsoDate(range.from), formatIsoDate(range.to));
      }} />
      <Button type="button" size="sm" variant="primary" disabled={!pending?.from || !pending.to} onClick={() => setOpen(false)}>Готово</Button>
    </div> : null}
  </div>;
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
