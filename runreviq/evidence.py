"""Versioned, human-reviewed evidence descriptions; never extraction authority."""
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class HttpScope(BaseModel):
    model_config = ConfigDict(strict=True, extra='forbid')
    mode: Literal['general', 'only']
    codes: list[int] = Field(max_length=20)

    @model_validator(mode='after')
    def check_codes(self):
        if (any(not 100 <= code <= 599 for code in self.codes)
                or len(set(self.codes)) != len(self.codes)
                or (self.mode == 'only') != bool(self.codes)):
            raise ValueError('Only scopes need distinct HTTP codes; general scopes have none')
        return self


class EvidenceUnit(BaseModel):
    model_config = ConfigDict(strict=True, extra='forbid')
    role: Literal['procedure', 'prohibition', 'historical', 'monitoring']
    start_line: int = Field(ge=1)
    end_line: int = Field(ge=1)


class EvidenceProfile(BaseModel):
    model_config = ConfigDict(strict=True, extra='forbid')
    schema_version: Literal[1]
    http_scope: HttpScope | None
    units: list[EvidenceUnit] = Field(max_length=20)

    @model_validator(mode='before')
    @classmethod
    def check_schema(cls, value):
        if not isinstance(value, dict) or type(value.get('schema_version')) is not int:
            raise ValueError('Schema version must be an integer')
        return value

    @model_validator(mode='after')
    def check_ranges(self):
        previous = 0
        for unit in self.units:
            if not unit.start_line > previous or not 0 <= unit.end_line - unit.start_line < 20:
                raise ValueError('Units must be ordered, non-overlapping ranges of at most 20 lines')
            previous = unit.end_line
        return self


def validate_profile(profile, content=None):
    parsed = EvidenceProfile.model_validate(profile)
    # Literal[1] alone can accept True; source metadata must be unambiguous.
    if type(profile['schema_version']) is not int:
        raise ValueError('Invalid evidence schema version')
    if content is not None:
        lines = content.splitlines()
        for unit in parsed.units:
            if unit.end_line > len(lines):
                raise ValueError('Evidence range is outside source')
            quote = lines[unit.start_line-1:unit.end_line]
            if not any(line.strip() and not line.lstrip().startswith('#') for line in quote):
                raise ValueError('Evidence needs source text, not just headings')
    return parsed.model_dump()


def validate_prohibitions(profile, claims):
    """A role label cannot invent a normative instruction."""
    for unit in profile['units']:
        if unit['role'] == 'prohibition' and not any(
            claim['rule'] == 'must_not' and 'citation' in claim
            and unit['start_line'] <= claim['citation']['start_line']
            and claim['citation']['end_line'] <= unit['end_line'] for claim in claims
        ):
            raise ValueError('Each prohibition needs a cited must_not claim inside its range')
