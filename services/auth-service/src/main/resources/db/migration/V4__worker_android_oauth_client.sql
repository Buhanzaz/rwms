DO $$
DECLARE
  old_client_id varchar(100);
BEGIN
  SELECT id
  INTO old_client_id
  FROM oauth2_registered_client
  WHERE client_id = 'rwms-worker';

  IF old_client_id IS NULL THEN
    RETURN;
  END IF;

  IF EXISTS (
    SELECT 1
    FROM oauth2_registered_client
    WHERE client_id = 'rwms-worker-android'
  ) THEN
    RAISE EXCEPTION
      'Both rwms-worker and rwms-worker-android OAuth clients exist; manual reconciliation is required';
  END IF;

  DELETE FROM oauth2_authorization_consent
  WHERE registered_client_id = old_client_id;

  DELETE FROM oauth2_authorization
  WHERE registered_client_id = old_client_id;

  UPDATE oauth2_registered_client
  SET client_id = 'rwms-worker-android',
      client_name = 'RWMS Рабочий'
  WHERE id = old_client_id;
END
$$;
