import json

import pytest

from app.errors import AgentError
from app.knowledge.documents import load_documents


def document(title="标题", heading="章节"):
    return f"""---
sourceId: test-source
title: {title}
sourceKind: PLATFORM
sourceUrl: null
reviewedAt: '2026-10-12'
---
# {heading}
NDVI方法正文。
"""


def test_frontmatter_and_empty_body_are_validated(tmp_path):
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"sources": ["source.md"]}), encoding="utf-8")
    source = tmp_path / "source.md"
    source.write_text(document(), encoding="utf-8")
    assert load_documents(manifest, tmp_path)[0].source_id == "test-source"
    source.write_text(document().replace("NDVI方法正文。", ""), encoding="utf-8")
    with pytest.raises(AgentError):
        load_documents(manifest, tmp_path)


@pytest.mark.parametrize("title,heading", [("标" * 256, "章节"), ("标题", "章" * 501)])
def test_metadata_cannot_exceed_response_budget(tmp_path, title, heading):
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"sources": ["source.md"]}), encoding="utf-8")
    (tmp_path / "source.md").write_text(document(title, heading), encoding="utf-8")
    with pytest.raises(AgentError):
        load_documents(manifest, tmp_path)
