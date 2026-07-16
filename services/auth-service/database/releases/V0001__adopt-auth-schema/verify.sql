DO $rwms$
DECLARE
    mismatch text;
BEGIN
    WITH expected(table_name, column_name, data_type, max_length, nullable) AS (
        VALUES
            ('auth_subject','id','uuid',NULL::integer,false),
            ('auth_subject','version','integer',NULL,false),
            ('auth_subject','principal_type','character varying',16,false),
            ('auth_subject','username','character varying',128,false),
            ('auth_subject','password_hash','character varying',255,false),
            ('auth_subject','first_name','character varying',128,true),
            ('auth_subject','last_name','character varying',128,true),
            ('auth_subject','email','character varying',255,true),
            ('auth_subject','time_zone_id','character varying',64,true),
            ('auth_subject','global_role','character varying',32,true),
            ('auth_subject','external_worker_id','character varying',128,true),
            ('auth_subject','warehouse_id','character varying',128,true),
            ('auth_subject','active','boolean',NULL,false),
            ('auth_subject','created_at','timestamp with time zone',NULL,false),
            ('auth_subject','updated_at','timestamp with time zone',NULL,false),
            ('user_warehouse_access','id','uuid',NULL,false),
            ('user_warehouse_access','version','integer',NULL,false),
            ('user_warehouse_access','user_id','uuid',NULL,false),
            ('user_warehouse_access','warehouse_id','character varying',128,false),
            ('user_warehouse_access','access_level','character varying',16,false),
            ('user_warehouse_access','comment_text','character varying',1000,true),
            ('user_warehouse_access','active','boolean',NULL,false),
            ('user_warehouse_access','created_at','timestamp with time zone',NULL,false),
            ('user_warehouse_access','updated_at','timestamp with time zone',NULL,false),
            ('oauth2_registered_client','id','character varying',100,false),
            ('oauth2_registered_client','client_id','character varying',100,false),
            ('oauth2_registered_client','client_id_issued_at','timestamp with time zone',NULL,false),
            ('oauth2_registered_client','client_secret','character varying',200,true),
            ('oauth2_registered_client','client_secret_expires_at','timestamp with time zone',NULL,true),
            ('oauth2_registered_client','client_name','character varying',200,false),
            ('oauth2_registered_client','client_authentication_methods','character varying',1000,false),
            ('oauth2_registered_client','authorization_grant_types','character varying',1000,false),
            ('oauth2_registered_client','redirect_uris','character varying',1000,true),
            ('oauth2_registered_client','post_logout_redirect_uris','character varying',1000,true),
            ('oauth2_registered_client','scopes','character varying',1000,false),
            ('oauth2_registered_client','client_settings','character varying',2000,false),
            ('oauth2_registered_client','token_settings','character varying',2000,false),
            ('oauth2_authorization','id','character varying',100,false),
            ('oauth2_authorization','registered_client_id','character varying',100,false),
            ('oauth2_authorization','principal_name','character varying',200,false),
            ('oauth2_authorization','authorization_grant_type','character varying',100,false),
            ('oauth2_authorization','authorized_scopes','character varying',1000,true),
            ('oauth2_authorization','attributes','text',NULL,true),
            ('oauth2_authorization','state','character varying',500,true),
            ('oauth2_authorization','authorization_code_value','text',NULL,true),
            ('oauth2_authorization','authorization_code_issued_at','timestamp with time zone',NULL,true),
            ('oauth2_authorization','authorization_code_expires_at','timestamp with time zone',NULL,true),
            ('oauth2_authorization','authorization_code_metadata','text',NULL,true),
            ('oauth2_authorization','access_token_value','text',NULL,true),
            ('oauth2_authorization','access_token_issued_at','timestamp with time zone',NULL,true),
            ('oauth2_authorization','access_token_expires_at','timestamp with time zone',NULL,true),
            ('oauth2_authorization','access_token_metadata','text',NULL,true),
            ('oauth2_authorization','access_token_type','character varying',100,true),
            ('oauth2_authorization','access_token_scopes','character varying',1000,true),
            ('oauth2_authorization','oidc_id_token_value','text',NULL,true),
            ('oauth2_authorization','oidc_id_token_issued_at','timestamp with time zone',NULL,true),
            ('oauth2_authorization','oidc_id_token_expires_at','timestamp with time zone',NULL,true),
            ('oauth2_authorization','oidc_id_token_metadata','text',NULL,true),
            ('oauth2_authorization','refresh_token_value','text',NULL,true),
            ('oauth2_authorization','refresh_token_issued_at','timestamp with time zone',NULL,true),
            ('oauth2_authorization','refresh_token_expires_at','timestamp with time zone',NULL,true),
            ('oauth2_authorization','refresh_token_metadata','text',NULL,true),
            ('oauth2_authorization','user_code_value','text',NULL,true),
            ('oauth2_authorization','user_code_issued_at','timestamp with time zone',NULL,true),
            ('oauth2_authorization','user_code_expires_at','timestamp with time zone',NULL,true),
            ('oauth2_authorization','user_code_metadata','text',NULL,true),
            ('oauth2_authorization','device_code_value','text',NULL,true),
            ('oauth2_authorization','device_code_issued_at','timestamp with time zone',NULL,true),
            ('oauth2_authorization','device_code_expires_at','timestamp with time zone',NULL,true),
            ('oauth2_authorization','device_code_metadata','text',NULL,true),
            ('oauth2_authorization_consent','registered_client_id','character varying',100,false),
            ('oauth2_authorization_consent','principal_name','character varying',200,false),
            ('oauth2_authorization_consent','authorities','character varying',1000,false)
    )
    SELECT e.table_name || '.' || e.column_name INTO mismatch
    FROM expected e
    LEFT JOIN information_schema.columns c
      ON c.table_schema = 'public'
     AND c.table_name = e.table_name
     AND c.column_name = e.column_name
    WHERE c.column_name IS NULL
       OR c.data_type <> e.data_type
       OR c.character_maximum_length IS DISTINCT FROM e.max_length
       OR (c.is_nullable = 'YES') IS DISTINCT FROM e.nullable
    LIMIT 1;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'Auth schema column invariant failed: %', mismatch;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name='auth_subject'
          AND column_name='version' AND column_default IN ('0', '0::integer')
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name='auth_subject'
          AND column_name='active' AND lower(column_default) IN ('true', 'true::boolean')
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name='user_warehouse_access'
          AND column_name='version' AND column_default IN ('0', '0::integer')
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name='user_warehouse_access'
          AND column_name='active' AND lower(column_default) IN ('true', 'true::boolean')
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name='oauth2_registered_client'
          AND column_name='client_id_issued_at' AND upper(column_default) = 'CURRENT_TIMESTAMP'
    ) THEN
        RAISE EXCEPTION 'Auth schema default invariant failed';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='auth_subject_pkey' AND contype='p'
            AND conrelid='public.auth_subject'::regclass AND pg_get_constraintdef(oid)='PRIMARY KEY (id)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uk_auth_subject_username' AND contype='u'
            AND conrelid='public.auth_subject'::regclass AND pg_get_constraintdef(oid)='UNIQUE (username)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uk_auth_subject_external_worker' AND contype='u'
            AND conrelid='public.auth_subject'::regclass AND pg_get_constraintdef(oid)='UNIQUE (external_worker_id)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_auth_subject_identity_fields' AND contype='c'
            AND conrelid='public.auth_subject'::regclass
            AND pg_get_constraintdef(oid) = 'CHECK (((((principal_type)::text = ''USER''::text) AND (global_role IS NOT NULL) AND (external_worker_id IS NULL) AND (warehouse_id IS NULL)) OR (((principal_type)::text = ''WORKER''::text) AND (global_role IS NULL) AND (external_worker_id IS NOT NULL) AND (warehouse_id IS NOT NULL))))')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='user_warehouse_access_pkey' AND contype='p'
            AND conrelid='public.user_warehouse_access'::regclass AND pg_get_constraintdef(oid)='PRIMARY KEY (id)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uk_user_warehouse_access' AND contype='u'
            AND conrelid='public.user_warehouse_access'::regclass
            AND pg_get_constraintdef(oid)='UNIQUE (user_id, warehouse_id)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_user_warehouse_access_user' AND contype='f'
            AND conrelid='public.user_warehouse_access'::regclass
            AND confrelid='public.auth_subject'::regclass AND confdeltype='c'
            AND pg_get_constraintdef(oid) ILIKE 'FOREIGN KEY (user_id) REFERENCES auth_subject(id) ON DELETE CASCADE')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='oauth2_registered_client_pkey' AND contype='p'
            AND conrelid='public.oauth2_registered_client'::regclass AND pg_get_constraintdef(oid)='PRIMARY KEY (id)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='oauth2_authorization_pkey' AND contype='p'
            AND conrelid='public.oauth2_authorization'::regclass AND pg_get_constraintdef(oid)='PRIMARY KEY (id)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='oauth2_authorization_consent_pkey' AND contype='p'
            AND conrelid='public.oauth2_authorization_consent'::regclass
            AND pg_get_constraintdef(oid)='PRIMARY KEY (registered_client_id, principal_name)') THEN
        RAISE EXCEPTION 'Auth schema constraint invariant failed';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND tablename='auth_subject'
            AND indexname='uk_auth_subject_username_ci'
            AND indexdef='CREATE UNIQUE INDEX uk_auth_subject_username_ci ON public.auth_subject USING btree (lower((username)::text))')
       OR NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND tablename='auth_subject'
            AND indexname='idx_auth_subject_principal_active'
            AND indexdef='CREATE INDEX idx_auth_subject_principal_active ON public.auth_subject USING btree (principal_type, active)')
       OR NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND tablename='oauth2_registered_client'
            AND indexname='uk_oauth2_registered_client_client_id'
            AND indexdef='CREATE UNIQUE INDEX uk_oauth2_registered_client_client_id ON public.oauth2_registered_client USING btree (client_id)')
       OR NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND tablename='oauth2_authorization'
            AND indexname='idx_oauth2_authorization_registered_client'
            AND indexdef='CREATE INDEX idx_oauth2_authorization_registered_client ON public.oauth2_authorization USING btree (registered_client_id)')
       OR NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND tablename='oauth2_authorization'
            AND indexname='idx_oauth2_authorization_principal'
            AND indexdef='CREATE INDEX idx_oauth2_authorization_principal ON public.oauth2_authorization USING btree (principal_name)') THEN
        RAISE EXCEPTION 'Auth schema index invariant failed';
    END IF;
END
$rwms$;
