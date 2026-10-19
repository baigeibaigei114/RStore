import math
from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    TypeAdapter,
    field_validator,
    model_validator,
)

Id = Annotated[str, StringConstraints(pattern=r"^[1-9][0-9]{0,18}$")]
Short = Annotated[str, StringConstraints(min_length=1, max_length=100)]
Text = Annotated[str, StringConstraints(min_length=1, max_length=2000)]


def valid_capture_time(value: str) -> str:
    date = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if date.tzinfo is None:
        raise ValueError("Timezone required")
    return value


CaptureTime = Annotated[str, StringConstraints(max_length=50, pattern=(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$"
)), AfterValidator(valid_capture_time)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)

    @model_validator(mode="before")
    @classmethod
    def literal_integer_types(cls, value):
        # Python equality makes True == 1 and 3.0 == 3; Literal alone does not
        # enforce the JSON integer wire type even with strict model settings.
        if isinstance(value, dict):
            for key in ("schemaVersion", "redBand", "nirBand"):
                if key in value and type(value[key]) is not int:
                    raise ValueError("Protocol version and bands must be JSON integers")
        return value


class ImageCandidate(StrictModel):
    id: Id
    name: Annotated[str, StringConstraints(min_length=1, max_length=255)]
    captureTime: CaptureTime | None = None
    sensor: str | None = Field(default=None, max_length=50)
    admission: Literal["PASSED"] = "PASSED"


class RegionCandidate(StrictModel):
    id: Id
    name: Short
    version: int = Field(ge=1)


class SelectionContext(StrictModel):
    imageCandidates: list[ImageCandidate] = Field(default_factory=list, max_length=10)
    regionCandidates: list[RegionCandidate] = Field(default_factory=list, max_length=10)
    selectedImageId: Id | None = None
    selectedRegionId: Id | None = None


class DecisionRequest(StrictModel):
    schemaVersion: Literal[1]
    operationId: str
    goal: Text
    selectionContext: SelectionContext
    observations: list[SelectionContext] = Field(default_factory=list, max_length=2)
    remainingCalls: int = Field(ge=1, le=12)

    @field_validator("operationId")
    @classmethod
    def uuid_valid(cls, value):
        UUID(value)
        return value


class AskUser(StrictModel):
    kind: Literal["ASK_USER"]
    question: str = Field(min_length=1, max_length=500)
    missingFields: list[Literal["imageId", "monitoringRegionId", "goalClarification"]] = Field(max_length=3)


class SearchArguments(StrictModel):
    keyword: str | None = Field(default=None, max_length=100)
    startTime: str | None = None
    endTime: str | None = None
    sensor: str | None = Field(default=None, max_length=50)
    maxCloudPercent: float | None = Field(default=None, ge=0, le=100)

    @model_validator(mode="after")
    def times(self):
        parsed = []
        for value in (self.startTime, self.endTime):
            if value is not None:
                date = datetime.fromisoformat(value.replace("Z", "+00:00"))
                if date.tzinfo is None:
                    raise ValueError("Timezone required")
                parsed.append(date)
        if len(parsed) == 2 and parsed[0] > parsed[1]:
            raise ValueError("Invalid time range")
        return self


class RegionArguments(StrictModel):
    keyword: str | None = Field(default=None, max_length=100)


class CallTool(StrictModel):
    kind: Literal["CALL_TOOL"]
    tool: Literal["searchImages", "listRegions"]
    arguments: dict

    @model_validator(mode="after")
    def arguments_valid(self):
        schema = SearchArguments if self.tool == "searchImages" else RegionArguments
        schema.model_validate(self.arguments)
        return self


class Bands(StrictModel):
    redBand: Literal[3]
    nirBand: Literal[4]


class ProposeNdvi(StrictModel):
    kind: Literal["PROPOSE_NDVI"]
    # Provider uses opaque handles; only the decision service maps these to current candidates.
    imageId: str = Field(pattern=r"^i[1-9][0-9]*$")
    monitoringRegionId: str = Field(pattern=r"^r[1-9][0-9]*$")
    params: Bands


class Unsupported(StrictModel):
    kind: Literal["UNSUPPORTED"]
    message: str = Field(min_length=1, max_length=500)


Action = Annotated[AskUser | CallTool | ProposeNdvi | Unsupported, Field(discriminator="kind")]
ACTION_ADAPTER = TypeAdapter(Action)


class AnswerRequest(StrictModel):
    schemaVersion: Literal[1]
    question: Text


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


class GeneratedExplanation(StrictModel):
    summary: str = Field(min_length=1, max_length=1000)
    claims: list[Claim] = Field(max_length=8)
    limitations: list[str] = Field(max_length=0)
    insufficientEvidence: bool


class Statistics(StrictModel):
    totalPixelCount: int = Field(gt=0)
    validPixelCount: int = Field(gt=0)
    invalidPixelCount: int = Field(ge=0)
    validPixelRatio: float = Field(gt=0, le=1)
    min: float
    max: float
    mean: float

    @model_validator(mode="after")
    def consistent(self):
        if self.totalPixelCount != self.validPixelCount + self.invalidPixelCount:
            raise ValueError("Pixel counts inconsistent")
        if not math.isclose(self.validPixelRatio, self.validPixelCount / self.totalPixelCount, abs_tol=1e-8):
            raise ValueError("Pixel ratio inconsistent")
        if not self.min <= self.mean <= self.max:
            raise ValueError("Statistics order invalid")
        return self


class RegionSnapshot(StrictModel):
    schemaVersion: Literal[1]
    regionId: int = Field(gt=0)
    name: Short
    version: int = Field(ge=1)
    crs: Literal["EPSG:4326"]
    pixelRule: Literal["center_covered"]
    geometry: dict


class ResultMetadata(StrictModel):
    schemaVersion: Literal[2]
    algorithm: Literal["NDVI"]
    scope: Literal["monitoring_region"]
    bandMapping: Bands
    coverageRatio: float = Field(ge=1, le=1)
    statistics: Statistics
    regionSnapshot: RegionSnapshot


class PublicImageSummary(StrictModel):
    sensor: str | None = Field(default=None, max_length=50)
    captureTime: CaptureTime | None = None


class ExplanationRequest(StrictModel):
    schemaVersion: Literal[1]
    operationId: str
    taskType: Literal["NDVI"]
    resultMetadata: ResultMetadata
    publicImageSummary: PublicImageSummary
    promptVersion: Literal["ndvi-explanation-v1"]

    @field_validator("operationId")
    @classmethod
    def uuid_valid(cls, value):
        UUID(value)
        return value
