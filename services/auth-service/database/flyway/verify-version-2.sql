DO $rwms$
DECLARE
    mismatch text;
BEGIN
    IF to_regclass('public.auth_subject') IS NULL
       OR to_regclass('public.user_warehouse_access') IS NULL
       OR to_regclass('public.oauth2_registered_client') IS NULL
       OR to_regclass('public.oauth2_authorization') IS NULL
       OR to_regclass('public.oauth2_authorization_consent') IS NULL THEN
        RAISE EXCEPTION 'Auth version 2 preflight requires the complete auth and OAuth schema';
    END IF;

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
    SELECT expected.table_name || '.' || expected.column_name INTO mismatch
    FROM expected
    LEFT JOIN information_schema.columns actual
      ON actual.table_schema = 'public'
     AND actual.table_name = expected.table_name
     AND actual.column_name = expected.column_name
    WHERE actual.column_name IS NULL
       OR actual.data_type <> expected.data_type
       OR actual.character_maximum_length IS DISTINCT FROM expected.max_length
       OR (actual.is_nullable = 'YES') IS DISTINCT FROM expected.nullable
    LIMIT 1;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'Auth version 2 column invariant failed: %', mismatch;
    END IF;

    IF (SELECT count(*) FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'auth_subject') <> 15
       OR (SELECT count(*) FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'user_warehouse_access') <> 9
       OR (SELECT count(*) FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'oauth2_registered_client') <> 13
       OR (SELECT count(*) FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'oauth2_authorization') <> 33
       OR (SELECT count(*) FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'oauth2_authorization_consent') <> 3 THEN
        RAISE EXCEPTION 'Auth version 2 contains unexpected columns';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'auth_subject'
              AND column_name = 'version' AND column_default IN ('0', '0::integer'))
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'auth_subject'
              AND column_name = 'active' AND lower(column_default) IN ('true', 'true::boolean'))
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'user_warehouse_access'
              AND column_name = 'version' AND column_default IN ('0', '0::integer'))
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'user_warehouse_access'
              AND column_name = 'active' AND lower(column_default) IN ('true', 'true::boolean'))
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'oauth2_registered_client'
              AND column_name = 'client_id_issued_at' AND upper(column_default) = 'CURRENT_TIMESTAMP') THEN
        RAISE EXCEPTION 'Auth version 2 default invariant failed';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
            WHERE conrelid = 'public.auth_subject'::regclass
              AND conname = 'auth_subject_pkey' AND contype = 'p'
              AND pg_get_constraintdef(oid) = 'PRIMARY KEY (id)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint
            WHERE conrelid = 'public.auth_subject'::regclass
              AND conname = 'uk_auth_subject_username' AND contype = 'u'
              AND pg_get_constraintdef(oid) = 'UNIQUE (username)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint
            WHERE conrelid = 'public.auth_subject'::regclass
              AND conname = 'uk_auth_subject_external_worker' AND contype = 'u'
              AND pg_get_constraintdef(oid) = 'UNIQUE (external_worker_id)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint
            WHERE conrelid = 'public.auth_subject'::regclass
              AND conname = 'ck_auth_subject_identity_fields' AND contype = 'c'
              AND pg_get_constraintdef(oid) = 'CHECK (((((principal_type)::text = ''USER''::text) AND (global_role IS NOT NULL) AND (external_worker_id IS NULL) AND (warehouse_id IS NULL)) OR (((principal_type)::text = ''WORKER''::text) AND (global_role IS NULL) AND (external_worker_id IS NOT NULL) AND (warehouse_id IS NOT NULL))))')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint
            WHERE conrelid = 'public.user_warehouse_access'::regclass
              AND conname = 'user_warehouse_access_pkey' AND contype = 'p'
              AND pg_get_constraintdef(oid) = 'PRIMARY KEY (id)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint
            WHERE conrelid = 'public.user_warehouse_access'::regclass
              AND conname = 'uk_user_warehouse_access' AND contype = 'u'
              AND pg_get_constraintdef(oid) = 'UNIQUE (user_id, warehouse_id)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint
            WHERE conrelid = 'public.user_warehouse_access'::regclass
              AND conname = 'fk_user_warehouse_access_user' AND contype = 'f'
              AND confrelid = 'public.auth_subject'::regclass AND confdeltype = 'c'
              AND pg_get_constraintdef(oid) ILIKE 'FOREIGN KEY (user_id) REFERENCES auth_subject(id) ON DELETE CASCADE')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint
            WHERE conrelid = 'public.oauth2_registered_client'::regclass
              AND conname = 'oauth2_registered_client_pkey' AND contype = 'p'
              AND pg_get_constraintdef(oid) = 'PRIMARY KEY (id)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint
            WHERE conrelid = 'public.oauth2_authorization'::regclass
              AND conname = 'oauth2_authorization_pkey' AND contype = 'p'
              AND pg_get_constraintdef(oid) = 'PRIMARY KEY (id)')
       OR NOT EXISTS (SELECT 1 FROM pg_constraint
            WHERE conrelid = 'public.oauth2_authorization_consent'::regclass
              AND conname = 'oauth2_authorization_consent_pkey' AND contype = 'p'
              AND pg_get_constraintdef(oid) = 'PRIMARY KEY (registered_client_id, principal_name)') THEN
        RAISE EXCEPTION 'Auth version 2 constraint invariant failed';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes
            WHERE schemaname = 'public' AND tablename = 'auth_subject'
              AND indexname = 'idx_auth_subject_principal_active'
              AND indexdef = 'CREATE INDEX idx_auth_subject_principal_active ON public.auth_subject USING btree (principal_type, active)')
       OR NOT EXISTS (SELECT 1 FROM pg_indexes
            WHERE schemaname = 'public' AND tablename = 'auth_subject'
              AND indexname = 'uk_auth_subject_username_ci'
              AND indexdef = 'CREATE UNIQUE INDEX uk_auth_subject_username_ci ON public.auth_subject USING btree (lower((username)::text))')
       OR NOT EXISTS (SELECT 1 FROM pg_indexes
            WHERE schemaname = 'public' AND tablename = 'oauth2_registered_client'
              AND indexname = 'uk_oauth2_registered_client_client_id'
              AND indexdef = 'CREATE UNIQUE INDEX uk_oauth2_registered_client_client_id ON public.oauth2_registered_client USING btree (client_id)')
       OR NOT EXISTS (SELECT 1 FROM pg_indexes
            WHERE schemaname = 'public' AND tablename = 'oauth2_authorization'
              AND indexname = 'idx_oauth2_authorization_registered_client'
              AND indexdef = 'CREATE INDEX idx_oauth2_authorization_registered_client ON public.oauth2_authorization USING btree (registered_client_id)')
       OR NOT EXISTS (SELECT 1 FROM pg_indexes
            WHERE schemaname = 'public' AND tablename = 'oauth2_authorization'
              AND indexname = 'idx_oauth2_authorization_principal'
              AND indexdef = 'CREATE INDEX idx_oauth2_authorization_principal ON public.oauth2_authorization USING btree (principal_name)') THEN
        RAISE EXCEPTION 'Auth version 2 index invariant failed';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.auth_subject
        WHERE global_role IS NOT NULL
          AND global_role NOT IN (
              'SYSTEM_ADMIN',
              'WMS_ADMIN',
              'WAREHOUSE_MANAGER',
              'RENTAL_MANAGER',
              'VIEWER'
          )
    ) THEN
        RAISE EXCEPTION 'Auth version 2 contains an unsupported global role';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.user_warehouse_access
        WHERE access_level NOT IN ('VIEW', 'EDIT', 'MANAGE')
    ) THEN
        RAISE EXCEPTION 'Auth version 2 contains an unsupported warehouse access level';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.user_warehouse_access access
        JOIN public.auth_subject subject ON subject.id = access.user_id
        WHERE subject.principal_type <> 'USER'
    ) THEN
        RAISE EXCEPTION 'Auth version 2 contains a warehouse grant for a non-USER subject';
    END IF;

    SELECT warehouse_id INTO mismatch
    FROM (
        SELECT warehouse_id FROM public.user_warehouse_access
        UNION ALL
        SELECT warehouse_id FROM public.auth_subject WHERE warehouse_id IS NOT NULL
    ) identifiers
    WHERE warehouse_id !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR warehouse_id IS DISTINCT FROM warehouse_id::uuid::text
    LIMIT 1;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'Auth version 2 contains a non-canonical warehouse identifier: %', mismatch;
    END IF;

    SELECT user_id::text INTO mismatch
    FROM public.user_warehouse_access
    GROUP BY user_id, warehouse_id
    HAVING count(*) > 1
    LIMIT 1;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'Auth version 2 contains duplicate warehouse grants for user %', mismatch;
    END IF;

    IF to_regclass('public.rwms_schema_history') IS NULL
       OR NOT EXISTS (SELECT 1 FROM public.rwms_schema_history WHERE version = '0001')
       OR NOT EXISTS (SELECT 1 FROM public.rwms_schema_history WHERE version = '0002')
       OR NOT EXISTS (
           SELECT 1 FROM public.rwms_schema_history
           WHERE version = '0001'
             AND checksum = '33e3e80bcbcaefae473649b87cc8fac89525e6218c615329b09eeb443877aa49'
       )
       OR NOT EXISTS (
           SELECT 1 FROM public.rwms_schema_history
           WHERE version = '0002'
             AND checksum = 'e4fadeb39b7947632923548875f1b311de2fad7810854b9696e32a4bd180aeb3'
       ) THEN
        RAISE EXCEPTION 'Auth version 2 custom schema history evidence is incomplete';
    END IF;

    IF to_regclass('public.databasechangelog') IS NULL
       OR to_regclass('public.databasechangeloglock') IS NULL THEN
        RAISE EXCEPTION 'Auth version 2 Liquibase history evidence is missing';
    END IF;
END
$rwms$;
