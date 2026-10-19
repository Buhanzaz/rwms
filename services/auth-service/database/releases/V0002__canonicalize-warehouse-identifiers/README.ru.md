# V0002: канонизация идентификаторов складов

[English version](README.md)

Этот data release сопоставляет только закрытый проверенный набор aliases `spb`
и `msk` с каноническими UUID Warehouse Service. Уже точный UUID text
нормализуется типом PostgreSQL UUID. Значения с лишними пробелами и любые
другие opaque identifiers приводят к отказу; никакое значение не угадывается.

Release блокирует сначала `auth_subject`, затем `user_warehouse_access`, до
любого update выполняет все проверки unmapped values и коллизий на
пользователя и при неоднозначности прерывает transaction. Изменённые строки
USER grant и WORKER subject сохраняют primary key, увеличивают optimistic
`version` и получают новый UTC `updated_at`. Строки не объединяются и не
удаляются.

Явное сопоставление в `apply.sql`, сохранённые row IDs и запись release
checksum/history являются audit trail. Компенсирующий rollback обязан
использовать проверенный F0/F1 backup, потому что после canonicalization
восстановить исходный alias однозначно невозможно.
