CREATE TABLE IF NOT EXISTS public.auth_subject (
    id uuid NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    principal_type varchar(16) NOT NULL,
    username varchar(128) NOT NULL,
    password_hash varchar(255) NOT NULL,
    first_name varchar(128),
    last_name varchar(128),
    email varchar(255),
    time_zone_id varchar(64),
    global_role varchar(32),
    external_worker_id varchar(128),
    warehouse_id varchar(128),
    active boolean DEFAULT true NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT auth_subject_pkey PRIMARY KEY (id),
    CONSTRAINT uk_auth_subject_username UNIQUE (username),
    CONSTRAINT uk_auth_subject_external_worker UNIQUE (external_worker_id),
    CONSTRAINT ck_auth_subject_identity_fields CHECK (
        (principal_type = 'USER' AND global_role IS NOT NULL
            AND external_worker_id IS NULL AND warehouse_id IS NULL)
        OR
        (principal_type = 'WORKER' AND global_role IS NULL
            AND external_worker_id IS NOT NULL AND warehouse_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_auth_subject_principal_active
    ON public.auth_subject (principal_type, active);
CREATE UNIQUE INDEX IF NOT EXISTS uk_auth_subject_username_ci
    ON public.auth_subject (lower(username));

CREATE TABLE IF NOT EXISTS public.user_warehouse_access (
    id uuid NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    user_id uuid NOT NULL,
    warehouse_id varchar(128) NOT NULL,
    access_level varchar(16) NOT NULL,
    comment_text varchar(1000),
    active boolean DEFAULT true NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT user_warehouse_access_pkey PRIMARY KEY (id),
    CONSTRAINT uk_user_warehouse_access UNIQUE (user_id, warehouse_id),
    CONSTRAINT fk_user_warehouse_access_user FOREIGN KEY (user_id)
        REFERENCES public.auth_subject (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS public.oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200),
    client_secret_expires_at timestamptz,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000),
    post_logout_redirect_uris varchar(1000),
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    CONSTRAINT oauth2_registered_client_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_oauth2_registered_client_client_id
    ON public.oauth2_registered_client (client_id);

CREATE TABLE IF NOT EXISTS public.oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000),
    attributes text,
    state varchar(500),
    authorization_code_value text,
    authorization_code_issued_at timestamptz,
    authorization_code_expires_at timestamptz,
    authorization_code_metadata text,
    access_token_value text,
    access_token_issued_at timestamptz,
    access_token_expires_at timestamptz,
    access_token_metadata text,
    access_token_type varchar(100),
    access_token_scopes varchar(1000),
    oidc_id_token_value text,
    oidc_id_token_issued_at timestamptz,
    oidc_id_token_expires_at timestamptz,
    oidc_id_token_metadata text,
    refresh_token_value text,
    refresh_token_issued_at timestamptz,
    refresh_token_expires_at timestamptz,
    refresh_token_metadata text,
    user_code_value text,
    user_code_issued_at timestamptz,
    user_code_expires_at timestamptz,
    user_code_metadata text,
    device_code_value text,
    device_code_issued_at timestamptz,
    device_code_expires_at timestamptz,
    device_code_metadata text,
    CONSTRAINT oauth2_authorization_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_oauth2_authorization_registered_client
    ON public.oauth2_authorization (registered_client_id);
CREATE INDEX IF NOT EXISTS idx_oauth2_authorization_principal
    ON public.oauth2_authorization (principal_name);

CREATE TABLE IF NOT EXISTS public.oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    CONSTRAINT oauth2_authorization_consent_pkey
        PRIMARY KEY (registered_client_id, principal_name)
);
