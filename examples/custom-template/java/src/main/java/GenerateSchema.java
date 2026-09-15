/**
 * Visit Report — victools/jsonschema-generator integration example.
 *
 * Defines the DTO as Java records, generates a JSON Schema, sends it to
 * the Tayra API, and deserializes the AI-generated response back into
 * the same record types for validation.
 *
 * Usage:
 *   # Print schema only:
 *   gradle run --quiet --args="--schema-only"
 *
 *   # Full round-trip — upload audio, send schema, validate response:
 *   gradle run --quiet --args="--endpoint <url> --api-key <key> --audio path/to/recording.mp3"
 *
 *   # Reuse an existing recording (one recording = one visit):
 *   gradle run --quiet --args="--endpoint <url> --api-key <key> --recording-id <uuid>"
 */

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

public class GenerateSchema {

    // ---------- DTO ----------

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
        @JsonProperty("heart_rate") @JsonPropertyDescription("Heart rate (bpm)") Optional<Integer> heartRate,
        @JsonProperty("blood_pressure") @JsonPropertyDescription("Blood pressure (e.g. 120/80 mmHg)") Optional<String> bloodPressure
    ) {}

    public record Diagnosis(
        @JsonProperty("diagnosis_text") @JsonPropertyDescription("Clinical diagnosis text") Optional<String> diagnosisText,
        @JsonProperty("diagnosis_type") @JsonPropertyDescription("Type of diagnosis") DiagnosisType diagnosisType
    ) {}

    public record Medication(
        @JsonPropertyDescription("Medication name") Optional<String> name,
        @JsonPropertyDescription("Dosage and frequency") Optional<String> dose,
        @JsonProperty("duration_days") @JsonPropertyDescription("Treatment duration in days") Optional<Integer> durationDays
    ) {}

    public record Report(
        @JsonProperty("chief_complaint") @JsonPropertyDescription("Primary reason for the visit") Optional<String> chiefComplaint,
        @JsonPropertyDescription("Urgency level of the consultation") Optional<UrgencyLevel> urgency,
        @JsonProperty("vital_signs") @JsonPropertyDescription("Vital signs measured during the visit") VitalSigns vitalSigns,
        @JsonPropertyDescription("List of diagnoses") List<Diagnosis> diagnoses,
        @JsonPropertyDescription("Prescribed medications") List<Medication> medications,
        @JsonPropertyDescription("Follow-up recommendations") List<String> recommendations
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VisitResponse(
        String id,
        @JsonProperty("content") JsonNode contentNode
    ) {}

    // ---------- Schema generation ----------

    static ObjectNode buildSchema() throws Exception {
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

        configBuilder.forFields()
            .withRequiredCheck(field -> true);
        configBuilder.forTypesInGeneral()
            .withPropertySorter((a, b) -> 0);

        SchemaGeneratorConfig config = configBuilder.build();

        ObjectNode schema = new SchemaGenerator(config).generateSchema(Report.class);
        schema.put("title", "Report");

        ObjectMapper mapper = new ObjectMapper();

        // Tayra metadata: ICD-10 coding on diagnosis_text
        ObjectNode diagDef = (ObjectNode) schema.path("$defs").path("Diagnosis");
        ObjectNode diagTextProp = (ObjectNode) diagDef.path("properties").path("diagnosis_text");
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("coding_system", "icd10");
        metadata.put("coding_format", "inline");
        diagTextProp.set("metadata", metadata);

        return schema;
    }

    // ---------- Helpers ----------

    static String uploadRecording(String endpoint, String apiKey, String audioPath, ObjectMapper mapper) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest presignReq = HttpRequest.newBuilder()
            .uri(URI.create(endpoint + "/recordings/presigned-url"))
            .header("Authorization", apiKey)
            .GET().build();
        HttpResponse<String> presignResp = client.send(presignReq, HttpResponse.BodyHandlers.ofString());
        JsonNode presigned = mapper.readTree(presignResp.body());
        String recordingId = presigned.get("id").asText();
        String uploadUrl = presigned.get("url").asText();
        System.out.println("[upload] recording_id=" + recordingId);

        byte[] fileData = Files.readAllBytes(Path.of(audioPath));
        HttpRequest uploadReq = HttpRequest.newBuilder()
            .uri(URI.create(uploadUrl))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(fileData)).build();
        client.send(uploadReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("[upload] " + Path.of(audioPath).getFileName() + " uploaded");
        return recordingId;
    }

    // ---------- CLI ----------

    static String arg(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) return args[i + 1];
        }
        return null;
    }

    static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (a.equals(flag)) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new Jdk8Module());
        ObjectNode schema = buildSchema();

        if (hasFlag(args, "--schema-only")) {
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema));
            return;
        }

        String endpoint = arg(args, "--endpoint");
        String apiKey = arg(args, "--api-key");
        String audioPath = arg(args, "--audio");
        String recordingId = arg(args, "--recording-id");
        String langCode = arg(args, "--lang-code");
        if (langCode == null) langCode = "uk";

        if (endpoint == null || apiKey == null || (audioPath == null && recordingId == null)) {
            System.err.println("Required: --endpoint <url> --api-key <key> --audio <path>|--recording-id <uuid>  (or --schema-only)");
            System.exit(1);
        }

        endpoint = endpoint.replaceAll("/$", "");
        if (audioPath != null) {
            recordingId = uploadRecording(endpoint, apiKey, audioPath, mapper);
        }

        System.out.println("\nSchema (" + schema.path("properties").size() + " top-level fields):");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema));

        String url = endpoint + "/visits";
        System.out.println("\nPOST " + url + " ...");

        ObjectNode payload = mapper.createObjectNode();
        payload.set("template", schema);
        payload.put("lang_code", langCode);
        payload.put("recording_id", recordingId);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();

        HttpResponse<String> resp = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build()
            .send(request, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            System.err.println("FAIL  HTTP " + resp.statusCode());
            System.err.println(resp.body().substring(0, Math.min(resp.body().length(), 2000)));
            System.exit(1);
        }

        JsonNode body = mapper.readTree(resp.body());
        JsonNode content = body.path("content");
        System.out.println("\nHTTP " + resp.statusCode() + " — raw content:");
        System.out.println(mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(content).substring(0, Math.min(
                mapper.writeValueAsString(content).length(), 2000)));

        try {
            Report report = mapper.treeToValue(content, Report.class);
            System.out.println("\nValidation: PASS");
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (Exception e) {
            System.err.println("\nValidation: FAIL");
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
