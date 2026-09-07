// Visit Report — System.Text.Json integration example.
//
// Defines the DTO as C# records, generates a JSON Schema with the built-in
// JsonSchemaExporter (.NET 9+), sends it to the Tayra API, and deserializes
// the AI-generated response back into the same record types for validation.
//
// Zero NuGet dependencies — uses only the .NET SDK.
//
// Usage:
//   dotnet run -- --schema-only
//   dotnet run -- --endpoint <url> --api-key <key> --audio path/to/recording.mp3
//   dotnet run -- --endpoint <url> --api-key <key> --recording-id <uuid>

using System.ComponentModel;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Schema;
using System.Text.Json.Serialization;
using System.Text.Json.Serialization.Metadata;

// ---------- JSON options (shared for schema gen + validation) ----------

var jsonOptions = new JsonSerializerOptions
{
    PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
    Converters = { new JsonStringEnumConverter(JsonNamingPolicy.SnakeCaseLower) },
    WriteIndented = true,
    TypeInfoResolver = new DefaultJsonTypeInfoResolver(),
};

// ---------- Schema generation ----------

var schemaNode = jsonOptions.GetJsonSchemaAsNode(typeof(Report), new JsonSchemaExporterOptions
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
var schemaObj = schemaNode.AsObject();
schemaObj["title"] = "Report";

// Tayra metadata: ICD-10 coding on diagnosis_text
var diagItems = schemaObj["properties"]?["diagnoses"]?["items"];
if (diagItems?["properties"]?["diagnosis_text"] is JsonObject diagText)
    diagText["metadata"] = JsonNode.Parse("""{"coding_system":"icd10","coding_format":"inline"}""");

// ---------- CLI ----------

string? Arg(string flag)
{
    var i = Array.IndexOf(args, flag);
    return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
}

if (args.Contains("--schema-only"))
{
    Console.WriteLine(schemaObj.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
    return;
}

var endpoint = Arg("--endpoint");
var apiKey = Arg("--api-key");
var audioPath = Arg("--audio");
var recordingId = Arg("--recording-id");
var langCode = Arg("--lang-code") ?? "uk";

if (endpoint is null || apiKey is null || (audioPath is null && recordingId is null))
{
    Console.Error.WriteLine(
        "Required: --endpoint <url> --api-key <key> --audio <path>|--recording-id <uuid>  (or --schema-only)");
    Environment.Exit(1);
}

endpoint = endpoint.TrimEnd('/');
using var http = new HttpClient();
http.DefaultRequestHeaders.Add("Authorization", apiKey);

if (audioPath is not null)
{
    var presignResp = await http.GetStringAsync($"{endpoint}/recordings/presigned-url");
    var presigned = JsonNode.Parse(presignResp)!.AsObject();
    recordingId = presigned["id"]!.GetValue<string>();
    var uploadUrl = presigned["url"]!.GetValue<string>();
    Console.WriteLine($"[upload] recording_id={recordingId}");

    var fileData = await File.ReadAllBytesAsync(audioPath);
    using var s3Http = new HttpClient();
    await s3Http.PutAsync(uploadUrl, new ByteArrayContent(fileData));
    Console.WriteLine($"[upload] {Path.GetFileName(audioPath)} uploaded");
}

var propCount = schemaObj["properties"]?.AsObject().Count ?? 0;
Console.WriteLine($"\nSchema ({propCount} top-level fields):");
Console.WriteLine(schemaObj.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));

// ---------- API call ----------

var url = $"{endpoint}/visits";
Console.WriteLine($"\nPOST {url} ...");

var payload = new JsonObject
{
    ["template"] = JsonNode.Parse(schemaObj.ToJsonString()),
    ["lang_code"] = langCode,
    ["recording_id"] = recordingId,
};

var resp = await http.PostAsync(url,
    new StringContent(payload.ToJsonString(), System.Text.Encoding.UTF8, "application/json"));

var bodyText = await resp.Content.ReadAsStringAsync();

if (!resp.IsSuccessStatusCode)
{
    Console.Error.WriteLine($"FAIL  HTTP {(int)resp.StatusCode}");
    Console.Error.WriteLine(bodyText[..Math.Min(bodyText.Length, 2000)]);
    Environment.Exit(1);
}

var body = JsonNode.Parse(bodyText)!.AsObject();
var content = body["content"];
Console.WriteLine($"\nHTTP {(int)resp.StatusCode} — raw content:");
var contentStr = content?.ToJsonString(new JsonSerializerOptions { WriteIndented = true }) ?? "";
Console.WriteLine(contentStr[..Math.Min(contentStr.Length, 2000)]);

// ---------- Validate with same DTO ----------

try
{
    var report = JsonSerializer.Deserialize<Report>(content!.ToJsonString(), jsonOptions)!;
    Console.WriteLine("\nValidation: PASS");
    Console.WriteLine(JsonSerializer.Serialize(report, jsonOptions));
}
catch (JsonException ex)
{
    Console.Error.WriteLine("\nValidation: FAIL");
    Console.Error.WriteLine(ex.Message);
    Environment.Exit(1);
}


// ---------- DTO ----------

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
