import { format } from 'date-fns';
import { ru } from 'date-fns/locale';
import { CalendarDays } from 'lucide-react';
import { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react';
import { DayPicker, type DateRange, type Matcher } from 'react-day-picker';
import 'react-day-picker/style.css';
import { formatIsoDate, parseIsoDate } from './date-value';
import { Button } from './ui';

const calendarLabels = {
  labelNav: () => 'Навигация по календарю',
  labelNext: () => 'Следующий месяц',
  labelPrevious: () => 'Предыдущий месяц',
  labelDayButton: (date: Date) => format(date, 'EEEE, d MMMM yyyy г.', { locale: ru }),
};

/** Keeps calendars above scrolling panels and returns keyboard focus to their trigger. */
function CalendarPopover({ id, label, anchorRef, onClose, children }: {
  id: string;
  label: string;
  anchorRef: RefObject<HTMLButtonElement | null>;
  onClose: (restoreFocus?: boolean) => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const popover = ref.current;
    if (!popover) return;
    popover.showPopover?.();
    const position = () => {
      const anchor = anchorRef.current?.getBoundingClientRect();
      if (!anchor) return;
      const { width, height } = popover.getBoundingClientRect();
      const below = window.innerHeight - anchor.bottom - 8;
      const above = anchor.top - 8;
      popover.style.left = `${Math.max(8, Math.min(anchor.right - width, window.innerWidth - width - 8))}px`;
      popover.style.top = `${Math.max(8, below >= height || below >= above
        ? Math.min(anchor.bottom + 8, window.innerHeight - height - 8)
        : anchor.top - height - 8)}px`;
    };
    position();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(position);
    observer?.observe(popover);
    window.addEventListener('resize', position);
    document.addEventListener('scroll', position, true);
    const outside = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!popover.contains(target) && !anchorRef.current?.contains(target)) onClose(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      event.stopPropagation();
      onClose();
    };
    const leave = (event: FocusEvent) => {
      if (event.relatedTarget instanceof Node
        && !popover.contains(event.relatedTarget)
        && !anchorRef.current?.contains(event.relatedTarget)) onClose(false);
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape, true);
    popover.addEventListener('focusout', leave);
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', position);
      document.removeEventListener('scroll', position, true);
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape, true);
      popover.removeEventListener('focusout', leave);
    };
  }, [anchorRef, onClose]);
  return <div id={id} ref={ref} popover="manual" className="date-picker__popover" role="dialog" aria-label={`Календарь: ${label}`}>{children}</div>;
}

/** Controlled hotel-style inclusive date-range calendar without timezone drift. */
export function DateRangePicker({ from, to, onChange, label, disabled = false }: {
  from: string;
  to: string;
  onChange: (from: string, to: string) => void;
  label: string;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const id = useId();
  const close = useCallback((restoreFocus = true) => {
    setOpen(false);
    if (restoreFocus) triggerRef.current?.focus();
  }, []);
  const committed = useMemo<DateRange | undefined>(() => {
    const fromDate = parseIsoDate(from);
    const toDate = parseIsoDate(to);
    return fromDate ? { from: fromDate, ...(toDate ? { to: toDate } : {}) } : undefined;
  }, [from, to]);
  const [pending, setPending] = useState<DateRange | undefined>(committed);
  useEffect(() => setPending(committed), [committed]);
  const formatRange = () => {
    if (!committed?.from) return 'Выберите период';
    if (!committed.to) return format(committed.from, 'd MMMM yyyy', { locale: ru });
    return `${format(committed.from, 'd MMM', { locale: ru })} — ${format(committed.to, 'd MMM yyyy', { locale: ru })}`;
  };
  return <div className="date-picker span-2">
    <button ref={triggerRef} type="button" className="date-picker__trigger" aria-label={label} aria-expanded={open} aria-controls={open ? id : undefined} aria-haspopup="dialog" disabled={disabled} onClick={() => {
      if (!open) setPending(committed);
      setOpen((current) => !current);
    }}>
      <CalendarDays size={15} aria-hidden="true" />
      <span>{formatRange()}</span>
    </button>
    {open ? <CalendarPopover id={id} label={label} anchorRef={triggerRef} onClose={close}>
      <DayPicker mode="range" locale={ru} labels={calendarLabels} autoFocus {...(pending ? { selected: pending } : {})} {...(pending?.from ? { defaultMonth: pending.from } : {})} showOutsideDays onSelect={(range) => {
        if (!range?.from) return;
        if (!pending?.from || pending.to) {
          setPending({ from: range.from });
          return;
        }
        setPending(range);
        if (range.to) onChange(formatIsoDate(range.from), formatIsoDate(range.to));
      }} />
      <div className="date-picker__footer"><Button type="button" size="sm" variant="primary" disabled={!pending?.from || !pending.to} onClick={() => close()}>Готово</Button></div>
    </CalendarPopover> : null}
  </div>;
}

/** Controlled Russian-language calendar that preserves an ISO date without timezone drift. */
export function DatePicker({ value, onChange, label, disabled = false, disabledDates, className = '' }: {
  value: string;
  onChange: (value: string) => void;
  label: string;
  disabled?: boolean;
  /** Dates that the owning workflow has closed or otherwise made unavailable. */
  disabledDates?: Matcher | Matcher[];
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const id = useId();
  const selected = useMemo(() => parseIsoDate(value), [value]);
  const close = useCallback((restoreFocus = true) => {
    setOpen(false);
    if (restoreFocus) triggerRef.current?.focus();
  }, []);

  return (
    <div className={`date-picker ${className}`}>
      <button
        ref={triggerRef}
        type="button"
        className="date-picker__trigger"
        aria-label={label}
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        aria-haspopup="dialog"
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
      >
        <CalendarDays size={15} aria-hidden="true" />
        <span>{selected ? format(selected, 'd MMMM yyyy', { locale: ru }) : 'Выберите дату'}</span>
      </button>
      {open ? (
        <CalendarPopover id={id} label={label} anchorRef={triggerRef} onClose={close}>
          <DayPicker
            mode="single"
            locale={ru}
            autoFocus
            {...(selected ? { selected, defaultMonth: selected } : {})}
            {...(disabledDates ? { disabled: disabledDates } : {})}
            showOutsideDays
            labels={calendarLabels}
            onSelect={(date) => {
              if (!date) return;
              onChange(formatIsoDate(date));
              close();
            }}
          />
        </CalendarPopover>
      ) : null}
    </div>
  );
}
