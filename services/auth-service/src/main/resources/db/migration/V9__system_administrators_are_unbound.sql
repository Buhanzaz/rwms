-- SYSTEM_ADMIN is a platform actor, not a member of any operating company.
ALTER TABLE auth_subject
    ALTER COLUMN company_id DROP NOT NULL;

UPDATE auth_subject
SET company_id = NULL
WHERE principal_type = 'USER'
  AND global_role = 'SYSTEM_ADMIN';
