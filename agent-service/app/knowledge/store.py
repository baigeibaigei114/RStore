from collections import Counter
from uuid import NAMESPACE_URL, uuid5

import psycopg
from pgvector.psycopg import register_vector
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from app.errors import AgentError


class PgKnowledgeStore:
    def __init__(self, dsn: str):
        self.dsn = dsn

    def connect(self):
        if not self.dsn:
            raise AgentError("KNOWLEDGE_UNAVAILABLE", "知识库连接未配置", 503)
        return psycopg.connect(self.dsn, connect_timeout=3, row_factory=dict_row,
                               options="-c statement_timeout=10000 -c lock_timeout=5000")

    @staticmethod
    def require_compatible(row, fingerprint):
        if row is None:
            raise AgentError("INDEX_NOT_READY", "知识库尚未建立索引", 503)
        if any(row["descriptor"].get(key) != value for key, value in fingerprint.items()):
            raise AgentError("INDEX_INCOMPATIBLE", "索引模型或切片配置不兼容，请显式重建", 503)
        return row

    def active(self, fingerprint):
        with self.connect() as conn:
            conn.execute("SET TRANSACTION READ ONLY")
            row = conn.execute("SELECT * FROM agent_knowledge.knowledge_index WHERE active AND status='READY'").fetchone()
            return self.require_compatible(row, fingerprint)

    def sync(self, index_id, descriptor, records, vectors):
        counts = Counter(record["document"].source_id for record in records)
        with self.connect() as conn:
            register_vector(conn)
            # One global writer lock also serializes activation of different releases.
            conn.execute("SELECT pg_advisory_xact_lock(hashtext('ndvi_knowledge_sync'))")
            existing = conn.execute("SELECT * FROM agent_knowledge.knowledge_index WHERE index_id=%s", (index_id,)).fetchone()
            if existing:
                if existing["descriptor"] != descriptor or existing["status"] != "READY":
                    raise AgentError("INDEX_INCOMPATIBLE", "已有索引版本不完整或不兼容", 503)
                if not existing["active"]:
                    conn.execute("UPDATE agent_knowledge.knowledge_index SET active=false WHERE active")
                    conn.execute("UPDATE agent_knowledge.knowledge_index SET active=true WHERE index_id=%s", (index_id,))
                return {"indexId": index_id, "status": "UNCHANGED", "chunkCount": len(records)}
            conn.execute("""
                INSERT INTO agent_knowledge.knowledge_index
                (index_id,embedding_model,embedding_revision,dimension,normalize,metric,query_instruction,
                 chunker_version,manifest_sha256,descriptor,status)
                VALUES (%s,%s,%s,512,true,'cosine',%s,%s,%s,%s,'STAGING')
                """, (index_id, descriptor["embeddingModel"], descriptor["embeddingRevision"],
                       descriptor["queryInstruction"], descriptor["chunkerVersion"],
                       descriptor["manifestSha256"], Jsonb(descriptor)))
            documents = {record["document"].source_id: record["document"] for record in records}
            for source_id, doc in documents.items():
                conn.execute("""
                    INSERT INTO agent_knowledge.knowledge_document
                    (id,index_id,source_id,title,source_kind,source_url,repo_path,sha256,reviewed_at,chunk_count)
                    VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                    """, (uuid5(NAMESPACE_URL, index_id + source_id), index_id, source_id, doc.title,
                           doc.source_kind, doc.source_url, doc.repo_path, doc.digest, doc.reviewed_at, counts[source_id]))
            with conn.cursor() as cursor:
                cursor.executemany("""
                    INSERT INTO agent_knowledge.knowledge_chunk
                    (chunk_id,document_id,ordinal,heading,heading_anchor,content,content_sha256,embedding)
                    VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
                    """, [(record["chunkId"], uuid5(NAMESPACE_URL, index_id + record["document"].source_id),
                           record["ordinal"], record["heading"], record["anchor"], record["content"],
                           record["contentSha256"], vector) for record, vector in zip(records, vectors, strict=True)])
            conn.execute("UPDATE agent_knowledge.knowledge_index SET active=false WHERE active")
            conn.execute("UPDATE agent_knowledge.knowledge_index SET status='READY',active=true WHERE index_id=%s", (index_id,))
        return {"indexId": index_id, "status": "INDEXED", "documentCount": len(documents), "chunkCount": len(records)}

    def retrieve(self, vector, fingerprint):
        # Active release and chunks are read from one repeatable snapshot; a concurrent
        # CLI activation cannot mix the corpus between these two statements.
        with self.connect() as conn:
            conn.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
            register_vector(conn)
            index = self.require_compatible(conn.execute(
                "SELECT * FROM agent_knowledge.knowledge_index WHERE active AND status='READY'"
            ).fetchone(), fingerprint)
            rows = conn.execute("""
                SELECT c.chunk_id,c.content,c.heading,c.heading_anchor,c.content_sha256,
                       d.source_id,d.title,d.source_kind,d.source_url,d.sha256 AS document_sha256,
                       c.embedding <=> %s AS distance
                FROM agent_knowledge.knowledge_chunk c
                JOIN agent_knowledge.knowledge_document d ON d.id=c.document_id
                WHERE d.index_id=%s ORDER BY distance,c.chunk_id LIMIT 5
                """, (vector, index["index_id"])).fetchall()
        citations = [{"citationId": f"C{number}", "sourceId": row["source_id"], "title": row["title"],
                      "sourceKind": row["source_kind"], "heading": row["heading"], "headingAnchor": row["heading_anchor"],
                      "chunkId": row["chunk_id"], "documentSha256": row["document_sha256"],
                      "chunkSha256": row["content_sha256"], "knowledgeIndexId": index["index_id"],
                      "excerpt": row["content"], "sourceUrl": row["source_url"]}
                     for number, row in enumerate(rows, start=1)]
        return {"knowledgeIndexId": index["index_id"], "citations": citations,
                "distances": [float(row["distance"]) for row in rows]}
