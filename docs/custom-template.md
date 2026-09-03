---
icon: lucide/braces
---

# Custom Template

Tayra accepts custom visit templates as **JSON Schema** documents. We use a pragmatic subset of [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12/json-schema-core).

The recommended approach is to **define your template as a DTO in your language** and generate JSON Schema from it using a standard library. This way your compiler or type checker validates the structure, and the schema stays in sync with your code. Tayra validates every schema on submission and provides clear errors if anything falls outside the supported subset.

If you prefer not to use a DTO library, you can write JSON Schema by hand — see the JSON example below.

| Language      | Recommended library                          |
|---------------|----------------------------------------------|
| Python        | `pydantic` &mdash; `Model.model_json_schema()`   |
| TypeScript/JS | `zod` + `zod-to-json-schema`                 |
| C#            | `System.Text.Json.Schema` (.NET 10+)         |
| Java          | `victools/jsonschema-generator`               |


The [supported subset](#json-schema-subset-reference) is listed at the end of this document: [allowed](#allowed-keywords) keywords are fully supported, [forbidden](#forbidden-keywords) keywords cause a validation error, and everything else is silently ignored.


## Tayra Metadata

Tayra recognizes a `metadata` object on any property in the schema. It controls Tayra's behavior for that field (e.g. enabling medical coding).

Add `metadata` as a sibling of `type`, `description`, etc.:

```json
{
  "diagnosis": {
    "type": ["string", "null"],
    "description": "Clinical diagnosis",
    "metadata": {
      "coding_system": "icd10",
      "coding_format": "inline"
    }
  }
}
```



### Coding Systems

| System  | Description                                  |
|---------|----------------------------------------------|
| `icd10` | International Classification of Diseases     |
| `icpc2` | International Classification of Primary Care |

### Coding Formats

| Format   | Description                                        |
|----------|----------------------------------------------------|
| `inline` | Codes are embedded inline alongside the text       |
| `codes`  | Only the codes are returned as a list              |
| `pairs`  | Code &ndash; disease name pairs are returned       |
| `string` | Codes are returned as a single comma-separated string |


## Schema Examples

All examples below define the same **Consultation Report** — a schema with typed properties, nullable fields, a nested object, an enum, and Tayra's `metadata` extension for ICD-10 coding. Each language produces a Tayra-compatible JSON Schema; minor differences (e.g. `anyOf` vs `type` array for nullable) are all accepted.

=== "Python"

    ```python
    from enum import Enum
    from pydantic import BaseModel, ConfigDict, Field


    class Severity(str, Enum):
        mild = "mild"
        moderate = "moderate"
        severe = "severe"


    class Vitals(BaseModel):
        blood_pressure: str = Field(description="Blood pressure reading")
        heart_rate: int | None = Field(default=None, description="Heart rate in bpm")


    class ConsultationReport(BaseModel):
        model_config = ConfigDict(title="Consultation Report")

        chief_complaint: str = Field(description="Patient's chief complaint")
        diagnosis: str | None = Field(
            default=None,
            description="Clinical diagnosis",
            json_schema_extra={
                "metadata": {"coding_system": "icd10", "coding_format": "inline"}
            },
        )
        severity: Severity = Field(description="Condition severity")
        vitals: Vitals = Field(description="Vital signs")
        treatment_plan: str | None = Field(default=None, description="Treatment plan")
        follow_up: bool = Field(default=False, description="Follow-up needed")


    schema = ConsultationReport.model_json_schema()
    ```

=== "TypeScript"

    ```typescript
    import { z } from "zod";
    import { zodToJsonSchema } from "zod-to-json-schema";

    const Severity = z.enum(["mild", "moderate", "severe"]);

    const Vitals = z.object({
      blood_pressure: z.string().describe("Blood pressure reading"),
      heart_rate: z.number().int().nullable().describe("Heart rate in bpm"),
    });

    const ConsultationReport = z.object({
      chief_complaint: z.string().describe("Patient's chief complaint"),
      diagnosis: z.string().nullable().describe("Clinical diagnosis"),
      severity: Severity.describe("Condition severity"),
      vitals: Vitals.describe("Vital signs"),
      treatment_plan: z.string().nullable().describe("Treatment plan"),
      follow_up: z.boolean().default(false).describe("Follow-up needed"),
    });

    const schema: Record<string, any> = zodToJsonSchema(ConsultationReport);
    schema.title = "Consultation Report";
    schema.properties.diagnosis.metadata = {
      coding_system: "icd10",
      coding_format: "inline",
    };
    ```

=== "C#"

    ```csharp
    using System.ComponentModel;
    using System.Text.Json;
    using System.Text.Json.Nodes;
    using System.Text.Json.Schema;
    using System.Text.Json.Serialization;

    var options = new JsonSerializerOptions
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.SnakeCaseLower) },
    };

    var schema = options.GetJsonSchemaAsNode(typeof(ConsultationReport));
    schema["title"] = "Consultation Report";
    schema["properties"]!["diagnosis"]!["metadata"] = new JsonObject
    {
        ["coding_system"] = "icd10",
        ["coding_format"] = "inline",
    };

    enum Severity { Mild, Moderate, Severe }

    record Vitals(
        [property: Description("Blood pressure reading")] string BloodPressure,
        [property: Description("Heart rate in bpm")] int? HeartRate
    );

    record ConsultationReport(
        [property: Description("Patient's chief complaint")] string ChiefComplaint,
        [property: Description("Clinical diagnosis")] string? Diagnosis,
        [property: Description("Condition severity")] Severity Severity,
        [property: Description("Vital signs")] Vitals Vitals,
        [property: Description("Treatment plan")] string? TreatmentPlan,
        [property: Description("Follow-up needed")] bool FollowUp = false
    );
    ```

=== "Java"

    ```java
    import java.util.Optional;
    import com.fasterxml.jackson.annotation.JsonProperty;
    import com.fasterxml.jackson.annotation.JsonPropertyDescription;
    import com.fasterxml.jackson.annotation.JsonValue;

    public enum Severity {
        MILD("mild"), MODERATE("moderate"), SEVERE("severe");
        private final String value;
        Severity(String value) { this.value = value; }
        @JsonValue public String getValue() { return value; }
    }

    public record Vitals(
        @JsonProperty("blood_pressure") @JsonPropertyDescription("Blood pressure reading") String bloodPressure,
        @JsonProperty("heart_rate") @JsonPropertyDescription("Heart rate in bpm") Optional<Integer> heartRate
    ) {}

    public record ConsultationReport(
        @JsonProperty("chief_complaint") @JsonPropertyDescription("Patient's chief complaint") String chiefComplaint,
        @JsonPropertyDescription("Clinical diagnosis") Optional<String> diagnosis,
        @JsonPropertyDescription("Condition severity") Severity severity,
        @JsonPropertyDescription("Vital signs") Vitals vitals,
        @JsonProperty("treatment_plan") @JsonPropertyDescription("Treatment plan") Optional<String> treatmentPlan,
        @JsonProperty("follow_up") @JsonPropertyDescription("Follow-up needed") boolean followUp
    ) {}

    // After generating schema, add Tayra metadata:
    ObjectNode diagProp = (ObjectNode) schema.path("properties").path("diagnosis");
    ObjectNode metadata = mapper.createObjectNode();
    metadata.put("coding_system", "icd10");
    metadata.put("coding_format", "inline");
    diagProp.set("metadata", metadata);
    ```

=== "cURL"

    ```json
    {
      "title": "Consultation Report",
      "type": "object",
      "properties": {
        "chief_complaint": {
          "type": "string",
          "description": "Patient's chief complaint"
        },
        "diagnosis": {
          "type": ["string", "null"],
          "description": "Clinical diagnosis",
          "metadata": {
            "coding_system": "icd10",
            "coding_format": "inline"
          }
        },
        "severity": {
          "type": "string",
          "description": "Condition severity",
          "enum": ["mild", "moderate", "severe"]
        },
        "vitals": {
          "type": "object",
          "description": "Vital signs",
          "properties": {
            "blood_pressure": {
              "type": "string",
              "description": "Blood pressure reading"
            },
            "heart_rate": {
              "type": ["integer", "null"],
              "description": "Heart rate in bpm"
            }
          },
          "required": ["blood_pressure"]
        },
        "treatment_plan": {
          "type": ["string", "null"],
          "description": "Treatment plan"
        },
        "follow_up": {
          "type": "boolean",
          "description": "Follow-up needed"
        }
      },
      "required": ["chief_complaint", "severity", "vitals"]
    }
    ```


## JSON Schema Subset Reference

### Allowed keywords

These keywords are fully supported. Your schema may use any combination of them.

| Category    | Keyword                | Notes                                                                 |
|-------------|------------------------|-----------------------------------------------------------------------|
| Structure   | `type`                 | Root must be `"object"`. Values: `string`, `number`, `integer`, `boolean`, `object`, `array`, `null` |
| Structure   | `properties`           | Object property definitions                                           |
| Structure   | `required`             | Array of required property names                                      |
| Structure   | `items`                | Schema for array elements                                             |
| Structure   | `additionalProperties` | Only `false` is accepted (when present)                               |
| Definitions | `$defs`                | Local type definitions                                                |
| Definitions | `$ref`                 | Only local references: `#/$defs/...`                                  |
| Annotation  | `title`                | Human-readable name for the field or schema                           |
| Annotation  | `description`          | Detailed description of the field                                     |
| Validation  | `enum`                 | List of allowed values |
| Validation  | `const`                | Single allowed value                                                  |
| Composition | `anyOf`                | **Nullable unions only**: branches must be type/`$ref` + `"null"`. Not allowed at root level |

### Nullable fields

Two syntaxes are accepted for nullable fields:

```json
// anyOf (preferred, used by Pydantic)
{ "anyOf": [{ "type": "string" }, { "type": "null" }] }

// type array (used by Zod, C#, Java generators)
{ "type": ["string", "null"] }
```

Both are accepted. Use whichever your library produces.

### Structural constraints

| Constraint                          | Limit         |
|-------------------------------------|---------------|
| Root `type`                         | Must be `"object"` |
| `anyOf` at root level               | Not allowed   |
| `$ref` targets                      | Local only (`#/$defs/...`) |
| Maximum nesting depth               | 10 levels     |
| Maximum total properties            | 5,000         |
| Maximum total string length (property names + enum/const values) | 120,000 chars |
| Maximum total enum values           | 1,000         |

### Forbidden keywords

These keywords will cause a validation error. None of them are emitted by the recommended DTO libraries.

| Keyword              | Reason                                  |
|----------------------|-----------------------------------------|
| `allOf`              | Not supported                           |
| `oneOf`              | Not supported &mdash; use `anyOf` for nullable |
| `not`                | Not supported                           |
| `dependentRequired`  | Not supported                           |
| `dependentSchemas`   | Not supported                           |
| `if` / `then` / `else` | Not supported                        |
| `discriminator`      | Not supported                           |

### Everything else

Keywords not listed as allowed or forbidden (e.g. `default`, `format`, `pattern`, `minimum`, `maximum`, `$schema`, `$id`) are **accepted without error** and silently ignored. You do not need to remove them from generated schemas.
