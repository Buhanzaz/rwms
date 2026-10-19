import { useState, type FormEvent } from 'react';
import { Button, Field, Modal } from '../../components/ui';
import type { ContractorCompany, ContractorCompanyInput } from './contractor-client';

/** Edits contact details for one city; company ownership is intentionally not editable. */
export function ContractorCompanyEditor({ company, warehouseName, busy, error, hasDrivers, onClose, onSave, onDelete }: {
  company: ContractorCompany | null;
  warehouseName: string;
  busy: boolean;
  error: string | null;
  hasDrivers: boolean;
  onClose: () => void;
  onSave: (input: ContractorCompanyInput) => Promise<void>;
  onDelete: (company: ContractorCompany) => Promise<void>;
}) {
  const [name, setName] = useState(company?.name ?? '');
  const [inn, setInn] = useState(company?.inn ?? '');
  const [contactName, setContactName] = useState(company?.contactName ?? '');
  const [phone, setPhone] = useState(company?.phone ?? '');
  const [email, setEmail] = useState(company?.email ?? '');
  const [address, setAddress] = useState(company?.address ?? '');
  const [comment, setComment] = useState(company?.comment ?? '');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const optional = (value: string) => value.trim() || null;
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy || !name.trim() || !phone.trim() || !/^(?:\d{10}|\d{12})$/u.test(inn)) return;
    void onSave({ name: name.trim(), inn, contactName: optional(contactName), phone: phone.trim(), email: optional(email), address: optional(address), comment: optional(comment) });
  };
  return (
    <Modal title={company ? 'Изменить наёмную компанию' : 'Добавить наёмную компанию'} description={`Компания и её водители закреплены за ${warehouseName}.`} onClose={() => { if (!busy) onClose(); }}>
      <form className="form-grid" onSubmit={submit}>
        {error ? <p className="field__error span-2" role="alert">{error}</p> : null}
        <Field className="span-2" label="Название компании" value={name} maxLength={256} required autoFocus onChange={(event) => setName(event.target.value)} />
        <Field className="span-2" label="ИНН" value={inn} inputMode="numeric" pattern="[0-9]{10}|[0-9]{12}" maxLength={12} required hint="10 цифр для организации или 12 для ИП" onChange={(event) => setInn(event.target.value)} />
        <Field className="span-2" label="Контактное лицо" value={contactName} maxLength={256} onChange={(event) => setContactName(event.target.value)} />
        <Field label="Телефон компании" type="tel" value={phone} maxLength={64} required onChange={(event) => setPhone(event.target.value)} />
        <Field label="Электронная почта" type="email" value={email} maxLength={256} onChange={(event) => setEmail(event.target.value)} />
        <Field className="span-2" label="Адрес" value={address} maxLength={1000} onChange={(event) => setAddress(event.target.value)} />
        <label className="field span-2"><span className="field__label">Примечание о компании</span><textarea className="input" value={comment} maxLength={2000} placeholder="Например, доступная техника и условия вызова" onChange={(event) => setComment(event.target.value)} /></label>
        {company && hasDrivers ? <p className="field__hint span-2">Для удаления компании сначала отвяжите её водителей в их профилях.</p> : null}
        {company && confirmDelete ? (
          <div className="span-2 contractor-delete-confirm" role="alert">
            <p>Удалить компанию «{company.name}» из каталога {warehouseName}?</p>
            <div className="toolbar-row contractor-editor__actions">
              <Button type="button" disabled={busy} onClick={() => setConfirmDelete(false)}>Не удалять</Button>
              <Button type="button" variant="danger" disabled={busy} onClick={() => void onDelete(company)}>{busy ? 'Удаляем…' : 'Удалить компанию'}</Button>
            </div>
          </div>
        ) : (
          <div className="span-2 toolbar-row contractor-editor__actions">
            {company ? <Button type="button" variant="danger" disabled={busy || hasDrivers} onClick={() => setConfirmDelete(true)}>Удалить</Button> : null}
            <span className="contractor-editor__spacer" />
            <Button type="button" disabled={busy} onClick={onClose}>Отмена</Button>
            <Button type="submit" variant="primary" disabled={busy || !name.trim() || !phone.trim() || !/^(?:\d{10}|\d{12})$/u.test(inn)}>{busy ? 'Сохраняем…' : 'Сохранить компанию'}</Button>
          </div>
        )}
      </form>
    </Modal>
  );
}
