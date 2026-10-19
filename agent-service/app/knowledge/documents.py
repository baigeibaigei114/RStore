import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import yaml

from app.config import ROOT
from app.errors import AgentError


def sha(text: str | bytes) -> str:
    return hashlib.sha256(text.encode("utf-8") if isinstance(text, str) else text).hexdigest()


def canonical(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


@dataclass(frozen=True)
class Document:
    source_id: str
    title: str
    source_kind: str
    source_url: str | None
    reviewed_at: str
    repo_path: str
    digest: str
    sections: list[tuple[str, str, str]]


def load_documents(manifest: Path, knowledge_root: Path = ROOT / "knowledge") -> list[Document]:
    manifest = manifest.resolve()
    knowledge_root = knowledge_root.resolve()
    if not manifest.is_relative_to(knowledge_root) or manifest.suffix != ".json":
        raise AgentError("MANIFEST_INVALID", "索引清单必须位于内置知识目录")
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if set(data) != {"sources"} or not isinstance(data["sources"], list) or not 1 <= len(data["sources"]) <= 50:
        raise AgentError("MANIFEST_INVALID", "索引清单结构不合法")
    docs, ids, paths = [], set(), set()
    for relative in data["sources"]:
        if not isinstance(relative, str):
            raise AgentError("MANIFEST_INVALID", "资料路径不合法")
        path = (knowledge_root / relative).resolve()
        if not path.is_relative_to(knowledge_root) or path.suffix != ".md" or path in paths:
            raise AgentError("MANIFEST_INVALID", "仅允许不重复的内置Markdown")
        raw = path.read_bytes()
        if len(raw) > 65536:
            raise AgentError("DOCUMENT_TOO_LARGE", "内置资料超过64KiB")
        text = raw.decode("utf-8")
        parts = text.split("---", 2)
        if len(parts) != 3 or parts[0].strip():
            raise AgentError("DOCUMENT_INVALID", "资料缺少frontmatter")
        meta = yaml.safe_load(parts[1])
        required = {"sourceId", "title", "sourceKind", "sourceUrl", "reviewedAt"}
        if not isinstance(meta, dict) or set(meta) != required:
            raise AgentError("DOCUMENT_INVALID", "资料元数据字段不合法")
        if not all(isinstance(meta[key], str) for key in required - {"sourceUrl"}):
            raise AgentError("DOCUMENT_INVALID", "资料元数据类型不合法")
        if not re.fullmatch(r"[a-z0-9-]{1,80}", meta["sourceId"]) or meta["sourceId"] in ids:
            raise AgentError("DOCUMENT_INVALID", "资料sourceId不合法或重复")
        if meta["sourceKind"] not in ("PLATFORM", "EXTERNAL_SCIENTIFIC"):
            raise AgentError("DOCUMENT_INVALID", "资料来源类型不合法")
        if not 1 <= len(meta["title"]) <= 255:
            raise AgentError("DOCUMENT_INVALID", "资料标题长度不合法")
        url = meta["sourceUrl"]
        if url is not None and (not isinstance(url, str) or urlparse(url).scheme != "https" or not urlparse(url).hostname):
            raise AgentError("DOCUMENT_INVALID", "资料来源必须是已登记HTTPS链接")
        if meta["sourceKind"] == "EXTERNAL_SCIENTIFIC" and not url:
            raise AgentError("DOCUMENT_INVALID", "外部科学资料须登记来源")
        if url and len(url) > 1024:
            raise AgentError("DOCUMENT_INVALID", "来源链接过长")
        heading = meta["title"]
        body: list[str] = []
        sections: list[tuple[str, str, str]] = []
        anchors: dict[str, int] = {}

        def flush():
            content = "\n".join(body).strip()
            if content:
                if len(heading) > 500:
                    raise AgentError("DOCUMENT_INVALID", "章节标题过长")
                anchor_base = re.sub(r"[^\w-]+", "-", heading.lower()).strip("-") or "section"
                anchors[anchor_base] = anchors.get(anchor_base, 0) + 1
                anchor = anchor_base + (f"-{anchors[anchor_base]}" if anchors[anchor_base] > 1 else "")
                sections.append((heading, anchor, content))

        for line in parts[2].splitlines():
            match = re.match(r"^#{1,6}\s+(.+)$", line)
            if match:
                flush()
                heading, body = match[1].strip(), []
            else:
                body.append(line)
        flush()
        if not sections:
            raise AgentError("DOCUMENT_INVALID", "资料正文为空")
        docs.append(Document(meta["sourceId"], meta["title"], meta["sourceKind"], url,
                             meta["reviewedAt"], relative, sha(raw), sections))
        ids.add(meta["sourceId"])
        paths.add(path)
    return sorted(docs, key=lambda doc: doc.source_id)


def split_section(text: str, embedding) -> list[str]:
    """Preserve every character; a token-checked prefix prevents tail truncation."""
    settings = embedding.settings
    chunks, start = [], 0
    while start < len(text):
        upper = min(len(text), start + settings.chunk_chars)
        # Token length is not assumed monotonic over character prefixes. Scan downward
        # from the bounded character target, checking the actual tokenizer each time.
        end = upper
        while end > start and embedding.token_count(text[start:end]) > settings.token_limit:
            end -= 1
        if end == start:
            raise AgentError("CHUNK_TOO_LONG", "单字符不能满足模型预算")
        content = text[start:end]
        if content.strip():
            chunks.append(content)
        if end == len(text):
            break
        overlap = min(settings.chunk_overlap, (end - start) // 2)
        start = end - overlap
    return chunks


def build_release(docs: list[Document], embedding):
    manifest_digest = sha(canonical([{ "sourceId": doc.source_id, "sha256": doc.digest } for doc in docs]))
    descriptor = dict(embedding.fingerprint, manifestSha256=manifest_digest)
    index_id = sha(canonical(descriptor))
    records = []
    for doc in docs:
        ordinal = 0
        for heading, anchor, body in doc.sections:
            for content in split_section(body, embedding):
                digest = sha(content)
                chunk_id = sha(canonical([index_id, doc.digest, heading, ordinal, digest]))
                records.append({"document": doc, "ordinal": ordinal, "heading": heading, "anchor": anchor,
                                "content": content, "contentSha256": digest, "chunkId": chunk_id})
                ordinal += 1
    return index_id, descriptor, records
