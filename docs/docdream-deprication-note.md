# Deprecation note for DocDream

## POST /visits/custom

This endpoint is deprecated. The functionality has been unified into `POST /visits/`, which now accepts both template-based and inline schema requests via the `schema_format` field.

### Migration guide

Replace calls to `POST /visits/custom` with `POST /visits/` using the following changes:

**1. Rename `input_schema` → `template`**

The DocDream DTO previously sent as `input_schema` is now sent as `template`.

**2. Add `schema_format: "docdream_v1"`**

This tells the API how to interpret the `template` field. Without it, the default is `"json_schema"` (raw JSON Schema).

**3. Add `lang_code`**

Required when providing an inline `template`. Specifies the language for AI summarization.
Accepted values: `"uk"`, `"en"`, `"pl"`, `"fr"`, `"lv"`.

### Example

Before:
```json
{
  "input_schema": { "Name": "...", "Sections": [ ... ] },
  "recording_id": "...",
  "doctor_id": "..."
}
```

After:
```json
{
  "template": { "Name": "...", "Sections": [ ... ] },
  "schema_format": "docdream_v1",
  "lang_code": "uk",
  "recording_id": "...",
  "doctor_id": "..."
}
```

The response format is unchanged.
