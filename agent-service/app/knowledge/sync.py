import argparse
import json
from pathlib import Path

from app.config import ROOT, Settings
from app.embedding import BgeEmbedding
from app.errors import AgentError
from app.knowledge.documents import build_release, load_documents
from app.knowledge.store import PgKnowledgeStore


def sync(manifest: Path, settings: Settings, embedding=None):
    embedding = embedding or BgeEmbedding(settings)
    docs = load_documents(manifest)
    index_id, descriptor, records = build_release(docs, embedding)
    store = PgKnowledgeStore(settings.index_database_url)
    # Avoid duplicate embedding work for an already compatible active release.
    try:
        active = store.active(embedding.fingerprint)
    except AgentError as error:
        if error.code not in ("INDEX_NOT_READY", "INDEX_INCOMPATIBLE"):
            raise
        active = None
    if active and active["index_id"] == index_id:
        return {"indexId": index_id, "status": "UNCHANGED", "chunkCount": len(records)}
    vectors = embedding.documents([record["content"] for record in records])
    return store.sync(index_id, descriptor, records, vectors)


def main():
    parser = argparse.ArgumentParser(description="Explicit builtin indexing; no migration or URL fetching")
    parser.add_argument("--manifest", type=Path, default=ROOT / "knowledge/manifest.json")
    args = parser.parse_args()
    result = sync(args.manifest, Settings.from_env())
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
