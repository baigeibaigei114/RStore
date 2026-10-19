import os
from dataclasses import replace
from pathlib import Path

import pytest

from app.config import REVISION, Settings
from app.embedding import BgeEmbedding
from app.knowledge.store import PgKnowledgeStore


@pytest.fixture(scope="session")
def real_settings():
    if os.getenv("AGENT_RUN_INTEGRATION") != "1":
        pytest.skip("Set AGENT_RUN_INTEGRATION=1 with isolated database credentials")
    settings = Settings.from_env()
    path = settings.model_path or str(Path.home() / ".cache/huggingface/hub/models--BAAI--bge-small-zh-v1.5/snapshots" / REVISION)
    return replace(settings, model_path=path)


@pytest.fixture(scope="session")
def real_embedding(real_settings):
    return BgeEmbedding(real_settings)


@pytest.fixture(scope="session")
def writer(real_settings):
    return PgKnowledgeStore(real_settings.index_database_url)


@pytest.fixture(scope="session")
def reader(real_settings):
    return PgKnowledgeStore(real_settings.database_url)
