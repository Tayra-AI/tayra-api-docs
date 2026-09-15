# Tayra JSON Schema — Integrator Examples

Four examples showing how to define a **Visit Report** DTO in your language,
generate a Tayra-compatible JSON Schema from it, send it to the API, and
validate the AI-generated response against the same DTO.

All examples define the same schema — chief complaint, urgency level,
vital signs, diagnoses (with ICD-10 coding metadata), medications, and
follow-up recommendations — so the output is directly comparable across
languages.

## Tayra metadata

Tayra recognizes a `metadata` object on any property in the schema.
It is not included in the AI output — it controls how Tayra post-processes
the result (e.g. medical coding).

Add it as a sibling of `type`, `description`, etc. on the property you want to
annotate:

```json
{
  "diagnosis_text": {
    "type": ["string", "null"],
    "description": "Clinical diagnosis text",
    "metadata": {
      "coding_system": "icd10",
      "coding_format": "inline"
    }
  }
}
```

## Examples

Each example supports two modes:

- **`--schema-only`** — print the JSON Schema and exit (useful for debugging)
- **Full round-trip** — send schema to the API, receive AI summary, validate it
  against the DTO

Required flags for full round-trip: `--endpoint`, `--api-key`, `--recording-id`.
Optional: `--lang-code` (default: `uk`).

### Python — Pydantic v2

```bash
cd python
pip install pydantic requests
python generate_schema.py --schema-only
python generate_schema.py --endpoint <url> --api-key <key> --recording-id <uuid>
```

Uses `Field(json_schema_extra={"metadata": {...}})` to embed Tayra metadata.
Validates API response via `Report.model_validate(content)`.

### TypeScript — Zod + zod-to-json-schema

```bash
cd typescript
npm install
npx tsx generate-schema.ts --schema-only
npx tsx generate-schema.ts --endpoint <url> --api-key <key> --recording-id <uuid>
```

Validates API response via `Report.safeParse(content)`.

### C# — System.Text.Json.Schema (.NET 10)

```bash
cd csharp
dotnet run -- --schema-only
dotnet run -- --endpoint <url> --api-key <key> --recording-id <uuid>
```

Uses `JsonSerializer.Deserialize<Report>()` to validate the API response
against the same record types used for schema generation.

### Java — victools/jsonschema-generator

```bash
cd java
gradle run --quiet --args="--schema-only"
gradle run --quiet --args="--endpoint <url> --api-key <key> --recording-id <uuid>"
```

Uses Jackson `ObjectMapper.treeToValue(content, Report.class)` to validate
the API response against the same record types.
