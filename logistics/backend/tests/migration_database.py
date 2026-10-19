"""Per-test PostgreSQL databases for destructive migration probes."""

from __future__ import annotations

import os
from collections.abc import Iterator
from uuid import uuid4

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import URL, make_url


def _psycopg_url(database_url: str) -> URL:
    """Normalize the explicit test-server URL for synchronous administration."""

    url = make_url(database_url)
    if not url.drivername.startswith("postgresql"):
        raise ValueError("MIGRATION_TEST_DATABASE_URL must select PostgreSQL")
    return url.set(drivername="postgresql+psycopg")


@pytest.fixture(name="disposable_migration_database_url")
def _disposable_migration_database_url() -> Iterator[str]:
    """Create one empty UUID-named database and drop only that database after the test."""

    configured_url = os.getenv("MIGRATION_TEST_DATABASE_URL")
    if not configured_url:
        pytest.skip("MIGRATION_TEST_DATABASE_URL is not configured")

    server_url = _psycopg_url(configured_url)
    database_name = f"rwms_migration_test_{uuid4().hex}"
    admin_engine = create_engine(
        server_url.set(database="postgres"),
        isolation_level="AUTOCOMMIT",
        pool_pre_ping=True,
    )
    created = False
    try:
        with admin_engine.connect() as connection:
            connection.exec_driver_sql(f'CREATE DATABASE "{database_name}"')
        created = True
        yield server_url.set(database=database_name).render_as_string(hide_password=False)
    finally:
        if created:
            with admin_engine.connect() as connection:
                connection.execute(
                    text(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                        "WHERE datname = :database_name AND pid <> pg_backend_pid()"
                    ),
                    {"database_name": database_name},
                ).all()
                connection.exec_driver_sql(f'DROP DATABASE IF EXISTS "{database_name}"')
        admin_engine.dispose()
