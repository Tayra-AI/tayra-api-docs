---
icon: lucide/braces
---

# Custom Template

Tayra accepts custom visit templates as **JSON Schema** documents. We support a pragmatic subset of [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12/json-schema-core) with a few extensions for DTO library compatibility.

The recommended approach is to **define your template as a DTO in your language** and generate JSON Schema from it using a standard library. This way your compiler or type checker validates the structure, and the schema stays in sync with your code. Tayra validates every schema on submission and provides clear errors if anything falls outside the supported subset.

If you prefer not to use a DTO library, you can write JSON Schema by hand — see the JSON example below.

| Language      | Recommended library                                          |
|---------------|--------------------------------------------------------------|
| Python        | `pydantic` 2.x &mdash; `Model.model_json_schema()`         |
| TypeScript/JS | `zod` &ge; 3.24 + `zod-to-json-schema` &ge; 3.24           |
| C#            | `System.Text.Json.Schema` (.NET 10+)                        |
| Java          | `victools/jsonschema-generator` 4.37 + Jackson 2.18         |


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

| Format   | Output type    | Description                                        |
|----------|----------------|----------------------------------------------------|
| `inline` | text           | Codes are embedded inline alongside the text        |
| `codes`  | `list[str]`    | Only the codes are returned as a list               |
| `pairs`  | `list[dict]`   | Code &ndash; name pairs with full details           |
| `string` | `str`          | Codes as a single comma-separated string            |

#### Output examples

Given a `diagnosis` field with ICD-10 codes `J06.9` (acute upper respiratory infection) and `R50.9` (fever):

**`inline`** &mdash; codes are embedded directly into the text field value:

```json
{
  "diagnosis": "J06.9 Acute upper respiratory infection, R50.9 fever"
}
```

**`codes`** &mdash; a separate field contains code strings:

```json
{
  "diagnosis": "Acute upper respiratory infection, fever",
  "icd10": ["J06.9", "R50.9"]
}
```

**`pairs`** &mdash; a separate field contains code&ndash;name pairs:

```json
{
  "diagnosis": "Acute upper respiratory infection, fever",
  "icd10": [
    {"code": "J06.9", "name": "Acute upper respiratory infection", "type": "primary"},
    {"code": "R50.9", "name": "Fever, unspecified", "type": "concomitant"}
  ]
}
```

**`string`** &mdash; a separate field contains codes as a comma-separated string:

```json
{
  "diagnosis": "Acute upper respiratory infection, fever",
  "icd10": "J06.9, R50.9"
}
```

For non-`inline` formats, the output field name defaults to the `coding_system` value (e.g. `icd10`).


## Schema Examples

All examples below define the same **Visit Report** &mdash; a schema with typed properties, nullable fields, nested objects, arrays, enums, and Tayra's `metadata` extension for ICD-10 coding. Each language produces a Tayra-compatible JSON Schema; minor differences (e.g. `anyOf` vs `type` array for nullable) are all accepted.

=== "Python"

    ```python
    # pip install pydantic>=2.0

    from enum import Enum
    from typing import Literal

    from pydantic import BaseModel, Field


    class UrgencyLevel(str, Enum):
        routine = "routine"
        urgent = "urgent"
        emergency = "emergency"


    class VitalSigns(BaseModel):
        weight: float | None = Field(description="Body weight in kg")
        height: float | None = Field(description="Height in cm")
        heart_rate: int | None = Field(description="Heart rate (bpm)")
        blood_pressure: str | None = Field(
            description="Blood pressure (e.g. 120/80 mmHg)"
        )


    class Diagnosis(BaseModel):
        diagnosis_text: str | None = Field(
            description="Clinical diagnosis text",
            json_schema_extra={
                "metadata": {"coding_system": "icd10", "coding_format": "inline"}
            },
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
        urgency: UrgencyLevel | None = Field(
            description="Urgency level of the consultation"
        )
        vital_signs: VitalSigns = Field(
            description="Vital signs measured during the visit"
        )
        diagnoses: list[Diagnosis] = Field(description="List of diagnoses")
        medications: list[Medication] = Field(description="Prescribed medications")
        recommendations: list[str] = Field(description="Follow-up recommendations")


    schema = Report.model_json_schema()
    ```

=== "TypeScript"

    ```typescript
    // npm install zod@^3.24 zod-to-json-schema@^3.24

    import { z } from "zod";
    import { zodToJsonSchema } from "zod-to-json-schema";

    const UrgencyLevel = z.enum(["routine", "urgent", "emergency"]);

    const VitalSigns = z.object({
      weight: z.number().nullable().describe("Body weight in kg"),
      height: z.number().nullable().describe("Height in cm"),
      heart_rate: z.number().int().nullable().describe("Heart rate (bpm)"),
      blood_pressure: z
        .string()
        .nullable()
        .describe("Blood pressure (e.g. 120/80 mmHg)"),
    });

    const Diagnosis = z.object({
      diagnosis_text: z.string().nullable().describe("Clinical diagnosis text"),
      diagnosis_type: z
        .enum(["primary", "secondary", "comorbid"])
        .describe("Type of diagnosis"),
    });

    const Medication = z.object({
      name: z.string().nullable().describe("Medication name"),
      dose: z.string().nullable().describe("Dosage and frequency"),
      duration_days: z
        .number()
        .int()
        .nullable()
        .describe("Treatment duration in days"),
    });

    const Report = z.object({
      chief_complaint: z
        .string()
        .nullable()
        .describe("Primary reason for the visit"),
      urgency: UrgencyLevel.nullable().describe(
        "Urgency level of the consultation"
      ),
      vital_signs: VitalSigns,
      diagnoses: z.array(Diagnosis).describe("List of diagnoses"),
      medications: z.array(Medication).describe("Prescribed medications"),
      recommendations: z.array(z.string()).describe("Follow-up recommendations"),
    });

    const schema: Record<string, any> = zodToJsonSchema(Report, {
      target: "jsonSchema2019-09",
      definitionPath: "$defs",
      definitions: { UrgencyLevel, VitalSigns, Diagnosis, Medication },
    });
    delete schema.$schema;
    schema.title = "Report";
    schema.properties.vital_signs.description =
      "Vital signs measured during the visit";

    // Tayra metadata
    schema.$defs.Diagnosis.properties.diagnosis_text.metadata = {
      coding_system: "icd10",
      coding_format: "inline",
    };
    ```

=== "C#"

    ```csharp
    // .NET 10 SDK — zero NuGet dependencies
    // <TargetFramework>net10.0</TargetFramework>

    using System.ComponentModel;
    using System.Text.Json;
    using System.Text.Json.Nodes;
    using System.Text.Json.Schema;
    using System.Text.Json.Serialization;
    using System.Text.Json.Serialization.Metadata;

    // --- DTO ---

    [JsonConverter(typeof(JsonStringEnumConverter<UrgencyLevel>))]
    enum UrgencyLevel { Routine, Urgent, Emergency }

    [JsonConverter(typeof(JsonStringEnumConverter<DiagnosisType>))]
    enum DiagnosisType { Primary, Secondary, Comorbid }

    record VitalSigns(
        [property: Description("Body weight in kg")] double? Weight,
        [property: Description("Height in cm")] double? Height,
        [property: Description("Heart rate (bpm)")] int? HeartRate,
        [property: Description("Blood pressure (e.g. 120/80 mmHg)")] string? BloodPressure
    );

    record Diagnosis(
        [property: Description("Clinical diagnosis text")] string? DiagnosisText,
        [property: Description("Type of diagnosis")] DiagnosisType DiagnosisType
    );

    record Medication(
        [property: Description("Medication name")] string? Name,
        [property: Description("Dosage and frequency")] string? Dose,
        [property: Description("Treatment duration in days")] int? DurationDays
    );

    record Report(
        [property: Description("Primary reason for the visit")] string? ChiefComplaint,
        [property: Description("Urgency level of the consultation")] UrgencyLevel? Urgency,
        [property: Description("Vital signs measured during the visit")] VitalSigns VitalSigns,
        [property: Description("List of diagnoses")] Diagnosis[] Diagnoses,
        [property: Description("Prescribed medications")] Medication[] Medications,
        [property: Description("Follow-up recommendations")] string[] Recommendations
    );

    // --- Configuration ---

    var jsonOptions = new JsonSerializerOptions
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.SnakeCaseLower) },
        TypeInfoResolver = new DefaultJsonTypeInfoResolver(),
    };

    var schema = jsonOptions.GetJsonSchemaAsNode(typeof(Report), new JsonSchemaExporterOptions
    {
        TreatNullObliviousAsNonNullable = true,
        TransformSchemaNode = (ctx, node) =>
        {
            var desc = ctx.PropertyInfo?.AttributeProvider?
                .GetCustomAttributes(typeof(DescriptionAttribute), false)
                .OfType<DescriptionAttribute>()
                .FirstOrDefault()?.Description;
            if (desc is not null && node is JsonObject obj)
                obj["description"] = desc;
            return node;
        },
    });
    schema["title"] = "Report";

    // Tayra metadata
    schema["properties"]!["diagnoses"]!["items"]!
        ["properties"]!["diagnosis_text"]!.AsObject()["metadata"] =
        JsonNode.Parse("""{"coding_system":"icd10","coding_format":"inline"}""");
    ```

=== "Java"

    ```java
    // build.gradle.kts dependencies:
    //   com.github.victools:jsonschema-generator:4.37.0
    //   com.github.victools:jsonschema-module-jackson:4.37.0
    //   com.fasterxml.jackson.core:jackson-databind:2.18.0
    //   com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.18.0

    import java.util.List;
    import java.util.Optional;

    import com.fasterxml.jackson.annotation.JsonProperty;
    import com.fasterxml.jackson.annotation.JsonPropertyDescription;
    import com.fasterxml.jackson.annotation.JsonValue;
    import com.fasterxml.jackson.databind.ObjectMapper;
    import com.fasterxml.jackson.databind.node.ObjectNode;
    import com.github.victools.jsonschema.generator.*;
    import com.github.victools.jsonschema.module.jackson.*;

    // --- DTO ---

    public enum UrgencyLevel {
        ROUTINE("routine"), URGENT("urgent"), EMERGENCY("emergency");
        private final String value;
        UrgencyLevel(String value) { this.value = value; }
        @JsonValue public String getValue() { return value; }
    }

    public enum DiagnosisType {
        PRIMARY("primary"), SECONDARY("secondary"), COMORBID("comorbid");
        private final String value;
        DiagnosisType(String value) { this.value = value; }
        @JsonValue public String getValue() { return value; }
    }

    public record VitalSigns(
        @JsonPropertyDescription("Body weight in kg") Optional<Double> weight,
        @JsonPropertyDescription("Height in cm") Optional<Double> height,
        @JsonProperty("heart_rate") @JsonPropertyDescription("Heart rate (bpm)")
            Optional<Integer> heartRate,
        @JsonProperty("blood_pressure")
            @JsonPropertyDescription("Blood pressure (e.g. 120/80 mmHg)")
            Optional<String> bloodPressure
    ) {}

    public record Diagnosis(
        @JsonProperty("diagnosis_text")
            @JsonPropertyDescription("Clinical diagnosis text")
            Optional<String> diagnosisText,
        @JsonProperty("diagnosis_type")
            @JsonPropertyDescription("Type of diagnosis")
            DiagnosisType diagnosisType
    ) {}

    public record Medication(
        @JsonPropertyDescription("Medication name") Optional<String> name,
        @JsonPropertyDescription("Dosage and frequency") Optional<String> dose,
        @JsonProperty("duration_days")
            @JsonPropertyDescription("Treatment duration in days")
            Optional<Integer> durationDays
    ) {}

    public record Report(
        @JsonProperty("chief_complaint")
            @JsonPropertyDescription("Primary reason for the visit")
            Optional<String> chiefComplaint,
        @JsonPropertyDescription("Urgency level of the consultation")
            Optional<UrgencyLevel> urgency,
        @JsonProperty("vital_signs")
            @JsonPropertyDescription("Vital signs measured during the visit")
            VitalSigns vitalSigns,
        @JsonPropertyDescription("List of diagnoses") List<Diagnosis> diagnoses,
        @JsonPropertyDescription("Prescribed medications") List<Medication> medications,
        @JsonPropertyDescription("Follow-up recommendations") List<String> recommendations
    ) {}

    // --- Configuration ---

    JacksonModule jacksonModule = new JacksonModule(
        JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
        JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE
    );

    SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
        SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON
    )
        .with(jacksonModule)
        .with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
        .with(Option.NULLABLE_ALWAYS_AS_ANYOF)
        .with(Option.INLINE_NULLABLE_SCHEMAS)
        .without(Option.SCHEMA_VERSION_INDICATOR);

    configBuilder.forFields().withRequiredCheck(field -> true);

    ObjectNode schema = new SchemaGenerator(configBuilder.build())
        .generateSchema(Report.class);
    schema.put("title", "Report");

    // Tayra metadata
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode diagTextProp = (ObjectNode) schema
        .path("$defs").path("Diagnosis")
        .path("properties").path("diagnosis_text");
    ObjectNode metadata = mapper.createObjectNode();
    metadata.put("coding_system", "icd10");
    metadata.put("coding_format", "inline");
    diagTextProp.set("metadata", metadata);
    ```

=== "cURL"

    ```json
    {
      "title": "Report",
      "type": "object",
      "properties": {
        "chief_complaint": {
          "type": ["string", "null"],
          "description": "Primary reason for the visit"
        },
        "urgency": {
          "anyOf": [
            {"type": "string", "enum": ["routine", "urgent", "emergency"]},
            {"type": "null"}
          ],
          "description": "Urgency level of the consultation"
        },
        "vital_signs": {
          "type": "object",
          "description": "Vital signs measured during the visit",
          "properties": {
            "weight": {
              "type": ["number", "null"],
              "description": "Body weight in kg"
            },
            "height": {
              "type": ["number", "null"],
              "description": "Height in cm"
            },
            "heart_rate": {
              "type": ["integer", "null"],
              "description": "Heart rate (bpm)"
            },
            "blood_pressure": {
              "type": ["string", "null"],
              "description": "Blood pressure (e.g. 120/80 mmHg)"
            }
          },
          "required": ["weight", "height", "heart_rate", "blood_pressure"]
        },
        "diagnoses": {
          "type": "array",
          "description": "List of diagnoses",
          "items": {
            "type": "object",
            "properties": {
              "diagnosis_text": {
                "type": ["string", "null"],
                "description": "Clinical diagnosis text",
                "metadata": {
                  "coding_system": "icd10",
                  "coding_format": "inline"
                }
              },
              "diagnosis_type": {
                "type": "string",
                "description": "Type of diagnosis",
                "enum": ["primary", "secondary", "comorbid"]
              }
            },
            "required": ["diagnosis_text", "diagnosis_type"]
          }
        },
        "medications": {
          "type": "array",
          "description": "Prescribed medications",
          "items": {
            "type": "object",
            "properties": {
              "name": {
                "type": ["string", "null"],
                "description": "Medication name"
              },
              "dose": {
                "type": ["string", "null"],
                "description": "Dosage and frequency"
              },
              "duration_days": {
                "type": ["integer", "null"],
                "description": "Treatment duration in days"
              }
            },
            "required": ["name", "dose", "duration_days"]
          }
        },
        "recommendations": {
          "type": "array",
          "description": "Follow-up recommendations",
          "items": {
            "type": "string"
          }
        }
      },
      "required": [
        "chief_complaint", "urgency", "vital_signs",
        "diagnoses", "medications", "recommendations"
      ]
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

Three syntaxes are accepted for nullable fields. Use whichever your library produces.

```json
// anyOf (Pydantic, Java victools)
{ "anyOf": [{ "type": "string" }, { "type": "null" }] }

// type array (Zod, C#)
{ "type": ["string", "null"] }

// null inside enum (C# nullable enums)
{ "enum": ["routine", "urgent", "emergency", null] }
```

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
