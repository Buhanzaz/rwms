FROM python:3.12-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

COPY pyproject.toml ./
COPY app ./app
COPY alembic.ini ./
COPY migrations ./migrations

FROM base AS test

RUN pip install --no-cache-dir '.[dev]'

COPY tests ./tests
COPY scripts ./scripts

CMD ["python", "-m", "pytest"]

FROM base AS restriction-indexer

RUN apt-get update \
    && apt-get install --yes --no-install-recommends osmium-tool=1.18.0-1 \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir .

CMD ["sh", "-ec", "alembic upgrade head && python -m app.services.osm_restriction_indexer"]

FROM base AS runtime

RUN pip install --no-cache-dir .

EXPOSE 8000

CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000"]
