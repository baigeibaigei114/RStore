import hashlib
from pathlib import Path

import numpy as np

from app.config import CHUNKER, MODEL, QUERY_INSTRUCTION, REVISION, Settings
from app.errors import AgentError


class BgeEmbedding:
    """Token-checked local CPU adapter. Never silently truncates or downloads weights."""

    def __init__(self, settings: Settings):
        from langchain_huggingface import HuggingFaceEmbeddings
        from transformers import AutoTokenizer

        if not 2 < settings.token_limit <= 512 or not 1 <= settings.chunk_chars <= 650 or not 0 <= settings.chunk_overlap < settings.chunk_chars:
            raise AgentError("EMBEDDING_CONFIG_INVALID", "切片配置不能超过固定模型窗口", 503)
        path = Path(settings.model_path).resolve()
        if not path.is_dir() or path.name != REVISION:
            raise AgentError("MODEL_CACHE_INVALID", "请配置固定revision的本地模型目录", 503)
        self.settings = settings
        self.tokenizer = AutoTokenizer.from_pretrained(str(path), local_files_only=True, trust_remote_code=False)
        self.adapter = HuggingFaceEmbeddings(
            model_name=str(path),
            model_kwargs={"device": "cpu", "local_files_only": True, "trust_remote_code": False},
            encode_kwargs={"normalize_embeddings": True, "batch_size": 16},
            show_progress=False,
        )
        self.fingerprint = {
            "embeddingModel": MODEL, "embeddingRevision": REVISION, "dimension": 512,
            "normalize": True, "metric": "cosine", "queryInstruction": QUERY_INSTRUCTION,
            "chunkerVersion": CHUNKER, "tokenLimit": settings.token_limit,
            "chunkChars": settings.chunk_chars, "overlapChars": settings.chunk_overlap,
            "modelConfigSha256": hashlib.sha256((path / "config.json").read_bytes()).hexdigest(),
        }

    def token_count(self, text: str) -> int:
        return len(self.tokenizer(text, add_special_tokens=True, truncation=False)["input_ids"])

    def _validate(self, text: str, query: bool = False):
        if not text.strip():
            raise AgentError("EMPTY_TEXT", "文本不能为空")
        if self.token_count(text) > self.settings.token_limit:
            raise AgentError("QUERY_TOO_LONG" if query else "CHUNK_TOO_LONG", "文本超过模型token上限，请缩短")

    @staticmethod
    def vectors(values):
        array = np.asarray(values, dtype=np.float32)
        if array.ndim != 2 or array.shape[1] != 512 or not np.isfinite(array).all():
            raise AgentError("EMBEDDING_INVALID", "向量维度或数值不合法", 503)
        if not np.allclose(np.linalg.norm(array, axis=1), 1.0, atol=1e-5):
            raise AgentError("EMBEDDING_INVALID", "向量未正确归一化", 503)
        return array

    def documents(self, texts: list[str]):
        for text in texts:
            self._validate(text)
        return self.vectors(self.adapter.embed_documents(texts))

    def query(self, text: str):
        actual = QUERY_INSTRUCTION + text
        self._validate(actual, query=True)
        return self.vectors([self.adapter.embed_query(actual)])[0]
