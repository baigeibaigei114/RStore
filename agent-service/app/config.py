import os
from dataclasses import dataclass, field
from pathlib import Path

MODEL = "BAAI/bge-small-zh-v1.5"
REVISION = "7999e1d3359715c523056ef9478215996d62a620"
QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："
CHUNKER = "md-char500-overlap80-token512-v2"
ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Settings:
    internal_token: str = field(default="", repr=False)
    database_url: str = field(default="", repr=False)
    index_database_url: str = field(default="", repr=False)
    model_path: str = ""
    deepseek_key: str = field(default="", repr=False)
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    deepseek_model: str = "deepseek-v4-flash"
    enabled: bool = False
    max_output_tokens: int = 1200
    timeout_seconds: float = 20
    max_provider_input_bytes: int = 24576
    max_concurrent_models: int = 2
    token_limit: int = 512
    chunk_chars: int = 500
    chunk_overlap: int = 80

    @classmethod
    def from_env(cls):
        return cls(
            internal_token=os.getenv("AGENT_INTERNAL_TOKEN", ""),
            database_url=os.getenv("AGENT_DATABASE_URL", ""),
            index_database_url=os.getenv("AGENT_INDEX_DATABASE_URL", ""),
            model_path=os.getenv("AGENT_MODEL_PATH", ""),
            deepseek_key=os.getenv("DEEPSEEK_API_KEY", ""),
            deepseek_base_url=os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1"),
            deepseek_model=os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash"),
            enabled=os.getenv("AGENT_ENABLED", "false").lower() == "true",
        )
