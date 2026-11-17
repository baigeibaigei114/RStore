from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

Text = Annotated[str, StringConstraints(min_length=1, max_length=2000)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)

    @model_validator(mode="before")
    @classmethod
    def literal_integer_types(cls, value):
        # Literal equality alone also accepts True and 1.0 as the version.
        if isinstance(value, dict) and "schemaVersion" in value and type(value["schemaVersion"]) is not int:
            raise ValueError("Protocol version must be a JSON integer")
        return value


class AnswerRequest(StrictModel):
    schemaVersion: Literal[1]
    question: Text


class PreparationRequest(StrictModel):
    schemaVersion: Literal[1]
    goal: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=500)]


class PreparationIntent(StrictModel):
    kind: Literal["REGION_NDVI", "UNSUPPORTED"]
    regionName: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)] | None = None
    imageName: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=255)] | None = None
    startDate: Annotated[str, StringConstraints(pattern=r"^\d{4}-\d{2}-\d{2}$")] | None = None
    endDate: Annotated[str, StringConstraints(pattern=r"^\d{4}-\d{2}-\d{2}$")] | None = None


class EvidenceQuote(StrictModel):
    citationId: str = Field(pattern=r"^C[1-5]$")
    quote: str = Field(min_length=1, max_length=650)


class Claim(StrictModel):
    text: str = Field(min_length=1, max_length=500)
    citationIds: list[str] = Field(min_length=1, max_length=5)
    evidenceQuotes: list[EvidenceQuote] = Field(min_length=1, max_length=5)
    metricRefs: list[Literal[
        "statistics.mean", "statistics.min", "statistics.max", "statistics.totalPixelCount",
        "statistics.validPixelCount", "statistics.invalidPixelCount", "statistics.validPixelRatio", "coverageRatio",
    ]] = Field(default_factory=list, max_length=8)


class GeneratedAnswer(StrictModel):
    answer: str = Field(min_length=1, max_length=1000)
    claims: list[Claim] = Field(max_length=8)
    insufficientEvidence: bool
