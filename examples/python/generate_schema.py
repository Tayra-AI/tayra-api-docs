"""Visit Report — Pydantic v2 integration example.

Defines the DTO, generates a JSON Schema, sends it to the Tayra API,
and validates the AI-generated response against the same DTO.

Usage:
    pip install pydantic requests

    # Print schema only:
    python generate_schema.py --schema-only

    # Full round-trip — upload audio, send schema, validate response:
    python generate_schema.py --endpoint <url> \
        --api-key <key> --audio path/to/recording.mp3

    # Reuse an existing recording (one recording = one visit):
    python generate_schema.py --endpoint <url> \
        --api-key <key> --recording-id <uuid>
"""

import argparse
import json
import sys
from enum import Enum
from pathlib import Path
from typing import Literal

import requests
from pydantic import BaseModel, Field, ValidationError


# ---------- DTO ----------

class UrgencyLevel(str, Enum):
    routine = "routine"
    urgent = "urgent"
    emergency = "emergency"


class VitalSigns(BaseModel):
    weight: float | None = Field(description="Body weight in kg")
    height: float | None = Field(description="Height in cm")
    heart_rate: int | None = Field(description="Heart rate (bpm)")
    blood_pressure: str | None = Field(description="Blood pressure (e.g. 120/80 mmHg)")


class Diagnosis(BaseModel):
    diagnosis_text: str | None = Field(
        description="Clinical diagnosis text",
        json_schema_extra={"metadata": {"coding_system": "icd10", "coding_format": "inline"}},
    )
    diagnosis_type: Literal["primary", "secondary", "comorbid"] = Field(
        description="Type of diagnosis",
    )


class Medication(BaseModel):
    name: str | None = Field(description="Medication name")
    dose: str | None = Field(description="Dosage and frequency")
    duration_days: int | None = Field(description="Treatment duration in days")


class Report(BaseModel):
    chief_complaint: str | None = Field(description="Primary reason for the visit")
    urgency: UrgencyLevel | None = Field(description="Urgency level of the consultation")
    vital_signs: VitalSigns = Field(description="Vital signs measured during the visit")
    diagnoses: list[Diagnosis] = Field(description="List of diagnoses")
    medications: list[Medication] = Field(description="Prescribed medications")
    recommendations: list[str] = Field(description="Follow-up recommendations")


# ---------- Helpers ----------

def upload_recording(endpoint: str, api_key: str, audio_path: Path) -> str:
    headers = {"Authorization": api_key}
    r = requests.get(f"{endpoint}/recordings/presigned-url", headers=headers)
    r.raise_for_status()
    presigned = r.json()
    recording_id = presigned["id"]
    print(f"[upload] recording_id={recording_id}")

    with open(audio_path, "rb") as f:
        requests.put(presigned["url"], data=f.read()).raise_for_status()
    print(f"[upload] {audio_path.name} uploaded")
    return recording_id


# ---------- CLI ----------

def main():
    parser = argparse.ArgumentParser(description="Tayra custom-template integration example (Python)")
    parser.add_argument("--endpoint", help="Tayra API base URL")
    parser.add_argument("--api-key", help="API key for authentication")
    parser.add_argument("--audio", type=Path, help="Path to audio file (will upload automatically)")
    parser.add_argument("--recording-id", help="UUID of an already-uploaded recording")
    parser.add_argument("--lang-code", default="uk", help="Language code (default: uk)")
    parser.add_argument("--schema-only", action="store_true", help="Print the JSON Schema and exit")
    args = parser.parse_args()

    schema = Report.model_json_schema()

    if args.schema_only:
        print(json.dumps(schema, indent=2))
        return

    if not args.endpoint or not args.api_key:
        parser.error("--endpoint and --api-key are required (or use --schema-only)")
    if not args.audio and not args.recording_id:
        parser.error("provide --audio <path> or --recording-id <uuid>")

    endpoint = args.endpoint.rstrip("/")

    if args.audio:
        recording_id = upload_recording(endpoint, args.api_key, args.audio)
    else:
        recording_id = args.recording_id

    print(f"\nSchema ({len(schema.get('properties', {}))} top-level fields):")
    print(json.dumps(schema, indent=2))

    print(f"\nPOST {endpoint}/visits ...")
    resp = requests.post(
        f"{endpoint}/visits",
        headers={"Authorization": args.api_key},
        json={
            "template": schema,
            "lang_code": args.lang_code,
            "recording_id": recording_id,
        },
    )

    if not resp.ok:
        print(f"FAIL  HTTP {resp.status_code}")
        print(resp.text[:2000])
        sys.exit(1)

    visit = resp.json()
    content = visit["content"]
    print(f"\nHTTP {resp.status_code} — raw content:")
    print(json.dumps(content, indent=2, ensure_ascii=False)[:2000])

    try:
        report = Report.model_validate(content)
        print("\nValidation: PASS")
        print(json.dumps(report.model_dump(mode="json"), indent=2, ensure_ascii=False))
    except ValidationError as e:
        print("\nValidation: FAIL")
        print(e)
        sys.exit(1)


if __name__ == "__main__":
    main()
