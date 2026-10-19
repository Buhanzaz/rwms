import { Building2, Mail, MoreHorizontal, Phone, Plus } from 'lucide-react';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { Badge, Button } from '../../components/ui';
import type { ContractorCompany } from './contractor-client';

/** Company contacts and its drivers, with the same actions available by button or right click. */
export function ContractorCompanyCard({ company, driverCount, disabled, onAddDriver, onEdit, children }: {
  company: ContractorCompany;
  driverCount: number;
  disabled: boolean;
  onAddDriver: () => void;
  onEdit: () => void;
  children: ReactNode;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuId = useId();
  const actions = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!menuOpen) return;
    menu.current?.querySelector<HTMLButtonElement>('button')?.focus();
    const outside = (event: PointerEvent) => {
      if (event.target instanceof Node && !actions.current?.contains(event.target)) setMenuOpen(false);
    };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [menuOpen]);
  const closeMenu = () => { setMenuOpen(false); trigger.current?.focus(); };
  return (
    <article className="contractor-company" aria-label={`Компания ${company.name}`} onContextMenu={(event) => {
      if (disabled) return;
      event.preventDefault();
      setMenuOpen(true);
    }}>
      <header className="contractor-company__header">
        <Building2 size={20} aria-hidden="true" />
        <div><h3>{company.name}</h3><p>ИНН {company.inn}</p></div>
        <div className="contractor-company__actions" ref={actions}>
          <button ref={trigger} type="button" className="button button--ghost button--sm" aria-label={`Действия компании ${company.name}`} aria-haspopup="menu" aria-expanded={menuOpen} aria-controls={menuId} disabled={disabled} onClick={() => setMenuOpen((open) => !open)} onKeyDown={(event) => {
            if (event.key === 'ArrowDown' || (event.shiftKey && event.key === 'F10')) { event.preventDefault(); setMenuOpen(true); }
          }}><MoreHorizontal size={18} /></button>
          {menuOpen ? <div ref={menu} id={menuId} className="contractor-company__menu" role="menu" aria-label={`Действия компании ${company.name}`} onKeyDown={(event) => {
            if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); closeMenu(); }
            if (event.key === 'Tab') setMenuOpen(false);
            if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
              event.preventDefault();
              const items = [...(menu.current?.querySelectorAll<HTMLButtonElement>('button') ?? [])];
              const index = items.indexOf(document.activeElement as HTMLButtonElement);
              const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
              items[next]?.focus();
            }
          }}>
            <button type="button" role="menuitem" onClick={() => { closeMenu(); onAddDriver(); }}>Добавить водителя в компанию</button>
            <button type="button" role="menuitem" onClick={() => { closeMenu(); onEdit(); }}>Изменить компанию</button>
          </div> : null}
        </div>
      </header>
      <div className="contractor-company__contacts">
        {company.contactName ? <strong>{company.contactName}</strong> : null}
        <a href={`tel:${company.phone.replace(/[^+\d]/gu, '')}`}><Phone size={14} aria-hidden="true" />{company.phone}</a>
        {company.email ? <a href={`mailto:${company.email}`}><Mail size={14} aria-hidden="true" />{company.email}</a> : null}
        {company.address ? <p>{company.address}</p> : null}
        {company.comment ? <p className="contractor-company__note">{company.comment}</p> : null}
      </div>
      <div className="contractor-company__drivers-heading"><span>Водители <Badge>{driverCount}</Badge></span><Button size="sm" disabled={disabled} onClick={onAddDriver}><Plus size={13} aria-hidden="true" />Добавить водителя</Button></div>
      <div className="entity-list contractor-company__drivers">{children}{driverCount === 0 ? <p className="field__hint">Добавьте водителя компании, чтобы назначать ему рейсы на выбранную дату.</p> : null}</div>
    </article>
  );
}
