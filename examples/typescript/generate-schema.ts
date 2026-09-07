/**
 * Visit Report — Zod + zod-to-json-schema integration example.
 *
 * Defines the DTO with Zod, generates a JSON Schema, sends it to the
 * Tayra API, and validates the AI-generated response with the same schema.
 *
 * Usage:
 *   npm install
 *
 *   # Print schema only:
 *   npx tsx generate-schema.ts --schema-only
 *
 *   # Full round-trip — upload audio, send schema, validate response:
 *   npx tsx generate-schema.ts --endpoint <url> \
 *       --api-key <key> --audio path/to/recording.mp3
 *
 *   # Reuse an existing recording (one recording = one visit):
 *   npx tsx generate-schema.ts --endpoint <url> \
 *       --api-key <key> --recording-id <uuid>
 */

import { readFileSync } from "node:fs";
import { z } from "zod";
import { zodToJsonSchema } from "zod-to-json-schema";

// ---------- DTO ----------

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

// ---------- Schema generation ----------

function buildSchema(): Record<string, any> {
  const schema: Record<string, any> = zodToJsonSchema(Report, {
    target: "jsonSchema2019-09",
    definitionPath: "$defs",
    definitions: { UrgencyLevel, VitalSigns, Diagnosis, Medication },
  });
  delete schema.$schema;
  schema.title = "Report";
  schema.properties.vital_signs.description = "Vital signs measured during the visit";

  // Tayra metadata: enable ICD-10 coding on diagnosis_text
  const diagDef = schema.$defs?.Diagnosis?.properties;
  if (diagDef?.diagnosis_text) {
    diagDef.diagnosis_text.metadata = {
      coding_system: "icd10",
      coding_format: "inline",
    };
  }
  return schema;
}

// ---------- CLI ----------

function parseArgs(argv: string[]) {
  const get = (flag: string) => {
    const i = argv.indexOf(flag);
    return i !== -1 && i + 1 < argv.length ? argv[i + 1] : undefined;
  };
  return {
    endpoint: get("--endpoint"),
    apiKey: get("--api-key"),
    audio: get("--audio"),
    recordingId: get("--recording-id"),
    langCode: get("--lang-code") ?? "uk",
    schemaOnly: argv.includes("--schema-only"),
  };
}

async function uploadRecording(
  endpoint: string,
  apiKey: string,
  audioPath: string
): Promise<string> {
  const r = await fetch(`${endpoint}/recordings/presigned-url`, {
    headers: { Authorization: apiKey },
  });
  const presigned = (await r.json()) as { id: string; url: string };
  console.log(`[upload] recording_id=${presigned.id}`);

  const fileData = readFileSync(audioPath);
  await fetch(presigned.url, { method: "PUT", body: fileData });
  console.log(`[upload] ${audioPath.split("/").pop()} uploaded`);
  return presigned.id;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const schema = buildSchema();

  if (args.schemaOnly) {
    console.log(JSON.stringify(schema, null, 2));
    return;
  }

  if (!args.endpoint || !args.apiKey || (!args.audio && !args.recordingId)) {
    console.error(
      "Required: --endpoint <url> --api-key <key> --audio <path>|--recording-id <uuid>  (or --schema-only)"
    );
    process.exit(1);
    return;
  }

  const { endpoint: rawEndpoint, apiKey, langCode } = args;
  const endpoint = rawEndpoint.replace(/\/$/, "");

  const recordingId = args.audio
    ? await uploadRecording(endpoint, apiKey, args.audio)
    : args.recordingId!;

  console.log(
    `\nSchema (${Object.keys(schema.properties).length} top-level fields):`
  );
  console.log(JSON.stringify(schema, null, 2));

  const url = `${endpoint}/visits`;
  console.log(`\nPOST ${url} ...`);

  const resp = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: apiKey,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      template: schema,
      lang_code: langCode,
      recording_id: recordingId,
    }),
  });

  const body = await resp.json();

  if (!resp.ok) {
    console.error(`FAIL  HTTP ${resp.status}`);
    console.error(JSON.stringify(body, null, 2).slice(0, 2000));
    process.exit(1);
  }

  const content = body.content;
  console.log(`\nHTTP ${resp.status} — raw content:`);
  console.log(JSON.stringify(content, null, 2).slice(0, 2000));

  const result = Report.safeParse(content);
  if (result.success) {
    console.log("\nValidation: PASS");
    console.log(JSON.stringify(result.data, null, 2));
  } else {
    console.error("\nValidation: FAIL");
    console.error(JSON.stringify(result.error.format(), null, 2));
    process.exit(1);
  }
}

main();
