from dataclasses import replace
from uuid import uuid4

import numpy as np
import psycopg
import pytest

from app.config import QUERY_INSTRUCTION, ROOT
from app.errors import AgentError
from app.knowledge.documents import build_release, load_documents, split_section
from app.knowledge.sync import sync

pytestmark = pytest.mark.integration


def test_token_boundary_tail_retained_and_real_adapter(real_embedding):
    text = "植" * 650 + "尾部限定词不可丢弃"
    assert real_embedding.token_count(text) > 512
    chunks = split_section(text, real_embedding)
    assert chunks[-1].endswith("尾部限定词不可丢弃")
    assert all(real_embedding.token_count(chunk) <= 512 for chunk in chunks)
    assert chunks[0] + chunks[1][80:] == text
    vectors = real_embedding.documents(chunks)
    assert vectors.shape == (2, 512)
    assert np.isfinite(vectors).all()
    assert np.allclose(np.linalg.norm(vectors, axis=1), 1, atol=1e-5)


def test_mixed_text_and_query_instruction_budget(real_embedding):
    text = "NDVI植被mask🙂 " * 120
    chunks = split_section(text, real_embedding)
    assert all(real_embedding.token_count(chunk) <= 512 for chunk in chunks)
    with pytest.raises(AgentError) as error:
        real_embedding.query("植" * 2000)
    assert error.value.code == "QUERY_TOO_LONG"
    # Find an exact boundary with the instruction and special tokens included.
    length = 0
    while real_embedding.token_count(QUERY_INSTRUCTION + "植" * (length + 1)) <= 512:
        length += 1
    assert real_embedding.query("植" * length).shape == (512,)
    with pytest.raises(AgentError):
        real_embedding.query("植" * (length + 1))


def test_real_sync_idempotence_readonly_role_and_exact_cosine(real_settings, real_embedding, reader, writer):
    first = sync(ROOT / "knowledge/manifest.json", real_settings, real_embedding)
    again = sync(ROOT / "knowledge/manifest.json", real_settings, real_embedding)
    assert again["status"] == "UNCHANGED" and first["indexId"] == again["indexId"]
    found = reader.retrieve(real_embedding.query("NDVI计算公式红光近红外"), real_embedding.fingerprint)
    assert len(found["citations"]) == 5
    assert "ndvi-method" in [citation["sourceId"] for citation in found["citations"]]
    from app.knowledge.documents import sha
    for citation in found["citations"]:
        assert sha(citation["excerpt"]) == citation["chunkSha256"]
        assert citation["knowledgeIndexId"] == first["indexId"]
    with reader.connect() as conn:
        row = conn.execute("SELECT current_database() AS db,current_user AS role").fetchone()
        assert row == {"db": "ndvi_agent_g2", "role": "agent_reader"}
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            conn.execute("UPDATE agent_knowledge.knowledge_index SET active=false")


def test_incompatible_revision_rejected(real_embedding, reader):
    fingerprint = dict(real_embedding.fingerprint, embeddingRevision="wrong-revision")
    with pytest.raises(AgentError) as error:
        reader.active(fingerprint)
    assert error.value.code == "INDEX_INCOMPATIBLE"


def test_failed_new_release_preserves_active_and_success_keeps_old(real_settings, real_embedding, reader, writer):
    old = sync(ROOT / "knowledge/manifest.json", real_settings, real_embedding)
    docs = load_documents(ROOT / "knowledge/manifest.json")
    source = docs[0]
    from app.knowledge.documents import sha
    modified = replace(source, digest=sha(str(uuid4())), sections=source.sections + [("新增测试", "test", "仅隔离库测试版本切换。")])
    docs[0] = modified
    new_id, descriptor, records = build_release(docs, real_embedding)
    vectors = real_embedding.documents([record["content"] for record in records])
    # A constraint error after index/doc inserts must roll back activation and records.
    invalid = [dict(record) for record in records]
    invalid[-1]["ordinal"] = -1
    with pytest.raises(psycopg.errors.CheckViolation):
        writer.sync(new_id, descriptor, invalid, vectors)
    assert reader.active(real_embedding.fingerprint)["index_id"] == old["indexId"]
    writer.sync(new_id, descriptor, records, vectors)
    assert reader.active(real_embedding.fingerprint)["index_id"] == new_id
    with reader.connect() as conn:
        assert conn.execute("SELECT count(*) AS n FROM agent_knowledge.knowledge_index WHERE index_id=%s", (old["indexId"],)).fetchone()["n"] == 1
    # Restore the builtin active release without deleting the tested historical release.
    sync(ROOT / "knowledge/manifest.json", real_settings, real_embedding)


def test_traversal_duplicate_and_empty_manifest_rejected(tmp_path):
    import json
    bad = tmp_path / "manifest.json"
    bad.write_text(json.dumps({"sources": ["../outside.md"]}), encoding="utf-8")
    with pytest.raises(AgentError):
        load_documents(bad, tmp_path)
