-- Explicit migration only. Verify instance, privileges and backup before applying to a business database.
BEGIN;
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE SCHEMA IF NOT EXISTS agent_knowledge;
CREATE TABLE IF NOT EXISTS agent_knowledge.knowledge_index (
    index_id CHAR(64) PRIMARY KEY,
    embedding_model TEXT NOT NULL,
    embedding_revision CHAR(40) NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension = 512),
    normalize BOOLEAN NOT NULL CHECK (normalize),
    metric TEXT NOT NULL CHECK (metric = 'cosine'),
    query_instruction TEXT NOT NULL,
    chunker_version TEXT NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    descriptor JSONB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('STAGING','READY','FAILED')),
    active BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (NOT active OR status = 'READY')
);
CREATE UNIQUE INDEX IF NOT EXISTS knowledge_single_active
    ON agent_knowledge.knowledge_index(active) WHERE active;
CREATE TABLE IF NOT EXISTS agent_knowledge.knowledge_document (
    id UUID PRIMARY KEY,
    index_id CHAR(64) NOT NULL REFERENCES agent_knowledge.knowledge_index(index_id),
    source_id TEXT NOT NULL,
    title TEXT NOT NULL,
    source_kind TEXT NOT NULL CHECK (source_kind IN ('PLATFORM','EXTERNAL_SCIENTIFIC')),
    source_url TEXT,
    repo_path TEXT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    reviewed_at DATE NOT NULL,
    chunk_count INTEGER NOT NULL CHECK (chunk_count > 0),
    UNIQUE(index_id,source_id)
);
CREATE TABLE IF NOT EXISTS agent_knowledge.knowledge_chunk (
    chunk_id CHAR(64) PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES agent_knowledge.knowledge_document(id),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    heading TEXT NOT NULL,
    heading_anchor TEXT NOT NULL,
    content TEXT NOT NULL CHECK (length(content) BETWEEN 1 AND 650),
    content_sha256 CHAR(64) NOT NULL,
    embedding vector(512) NOT NULL CHECK (vector_norm(embedding) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(document_id,ordinal)
);
CREATE INDEX IF NOT EXISTS knowledge_chunk_document
    ON agent_knowledge.knowledge_chunk(document_id);
COMMIT;
