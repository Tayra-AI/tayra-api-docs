---
icon: lucide/app-window
---

# Embedded Page Integration Guide

!!! warning "Placeholders"

    Values shown in `{{MONOSPACE}}` are confirmed at setup and are not yet fixed.

### Prerequisites

1. Obtain your API key from the Tayra team during onboarding. Send as `Authorization: Bearer <api_key>`. Store server-side only.
2. Allow outbound HTTPS from clinician machines to `{{API_BASE_URL}}` and `{{APP_BASE_URL}}`. All connections are outbound — nothing connects inbound from our side.

## Integration flow

Tayra runs as an Embedded View inside your MIS. For each encounter you create a session over HTTPS, supplying the note structure you want back. You receive a URL to embed. The clinician records and reviews the consultation inside that view. You long-poll the session until the note appears, then write it into the patient chart.

**Everything is ordinary outbound HTTPS.** No inbound access, no open ports, no persistent protocol.

1. **Create a session** — one POST returns a `session_id` and a launch URL (the ID is unguessable — safe to pass to the workstation, no other credentials needed there)
2. **Embed the view** — load the URL in an iframe or WebView with microphone permission
3. **Poll for the note** — GET returns `null` while in progress, the finished note once the clinician submits

```mermaid
sequenceDiagram
  autonumber
  participant E as Your MIS
  participant T as Tayra API
  participant V as Embedded View
  E->>T: POST /v1/sessions
  T-->>E: session_id, launch_url
  E->>V: load launch_url in iframe / WebView
  Note over V: Clinician records, reviews, submits
  loop until content or 404
    E->>T: GET /v1/sessions/{id} (held up to <?embedded_page.polling_timeout?> s)
    T-->>E: 200
  end
  E->>E: write result into the chart
```

## 1. Create a session

!!! warning "No patient identifiers in requests"

    Tayra receives no patient data. `title` is the only free-text field you send. **Never put names, DOB, MRNs, or any identifier in it.**

Call when the clinician opens the encounter, not in advance. The launch URL is short-lived.

```
POST /v1/sessions
Authorization: Bearer <api_key>
```

**Option A** — you define the note structure:

```json
{ "title":    "Follow-up",
  "locale":   "en-US",

  "template": {...}
}
```

**Option B** — use a pre-configured template:

```json
{ 
  "title":       "Follow-up",
  "locale":      "en-US",

  "template_id": "wf903-..." 
}
```

**200 OK**

```json
{ 
  "session_id":  "8h4f-...",
  "launch_url":  "{{APP_BASE_URL}}/s?t=...",
  "expires_at":  "2026-08-12T09:15:02Z"
}
```

### Request fields

| Field | Requirement |
|---|---|
| `template` | **Required unless you send `template_id`.** The note structure: field identifiers, labels, and optional hints. Up to `{{MAX_FIELDS}}` fields; labels up to `{{MAX_LABEL_LEN}}` characters. |
| `template_id` | **Alternative to `template`.** Names a pre-configured note structure held for your tenant. Send exactly one of `template` or `template_id`. |
| `title` | Optional. Shown in the view header so the clinician can confirm the encounter. **No patient identifiers.** |
| `locale` | **Required.** Transcription and interface language. |


## 2. Embed the view

Load `launch_url` in an iframe or native WebView. It occupies the space it is given.

```html
<iframe
  src="{launch_url}"
  allow="microphone"
  style="width:100%;min-width:720px;height:100%;min-height:640px;border:0">
</iframe>
```

| Requirement | Detail |
|---|---|
| **Load promptly** | Single-use token, valid **60 seconds**. Expired → `launch_token_expired`. Already used → `launch_token_consumed`. |
| **Do not reload** | Reloading or unmounting invalidates the view. Use `relaunch` for a fresh URL. |
| **Microphone** | iframe: `allow="microphone"`. Desktop WebView: grant via host permission API. |
| **Size** | Minimum 720 × 640 CSS pixels; responsive above that. |

### Relaunch

```
POST /v1/sessions/{session_id}/relaunch
```

**200 OK**

```json
{ "launch_url": "...", "expires_at": "..." }
```

The consultation continues where it left off.

Diagnostic page at `{{APP_BASE_URL}}/preflight` reports mic permission, connectivity, and latency without credentials — useful for validating new workstations.

## 3. Poll for the note

Long-poll the session. The response is either `content: null` (still in progress) or the finished note.

```
GET /v1/sessions/{session_id}?wait=<?embedded_page.polling_timeout?>
```

In progress — **200 OK**

```json
{ "content": null }
```

Clinician submitted — **200 OK**

```json
{ 
  "content": {...},
  "duration_s":   754,
  "finalized_at": "2026-08-12T09:32:04Z",
  ...
}
```

Session cancelled or deleted — **404 Not Found**

```json
{ "error": { "code": "session_not_found" } }
```

### Loop mechanics

1. Send `GET` with `wait`. Server holds until change or timeout.
2. On `200` with `content: null`: keep polling.
3. On `200` with `content: { ... }`: write to chart. Done.
4. On `404`: session was cancelled or expired. Stop polling.

### Practical notes

| Concern | What to do |
|---|---|
| **Client timeout** | Set above the wait — e.g. `<?embedded_page.polling_timeout?>` + 15 s. |
| **If long-polling is awkward** | Omit `wait`; poll every 5 s instead. |
| **Failures** | Reissue on drop or timeout. Back off on repeated `5xx`. |
| **Concurrency** | One loop per session. |
| **Note retention** | Finalized notes are retained for `{{NOTE_RETENTION}}` as a recovery backstop. |


## Errors

```json
{ "error": {
    "code":      "session_not_found",
    "message":   "Unknown or expired session.",
    "retryable": false,
    "trace_id":  "trc_9f2a..." } }
```

| Code | HTTP | Action |
|---|---|---|
| `launch_token_expired` | 410 | URL older than 60 s. Call `relaunch`. |
| `launch_token_consumed` | 410 | URL already used. Call `relaunch`. |
| `session_not_found` | 404 | Session expired or cancelled. Stop polling. |
| `rate_limited` | 429 | Retry after `retry_after_ms`. |
| `internal` | 5xx | Retry with backoff. Quote `trace_id` in support requests. |

Standard errors (`400`, `401`, `409`) use the same envelope and are self-explanatory during development.

## Limits

| Limit | Value |
|---|---|
| Fields per note | `{{MAX_FIELDS}}` |
| Field label length | `{{MAX_LABEL_LEN}}` |
| Launch URL validity | 60 s, single use |
| Max consultation length | `{{MAX_DURATION}}` |
| Inactivity timeout | `{{IDLE_TIMEOUT}}` |
| Note retention | `{{NOTE_RETENTION}}` |
| Max long-poll wait | `<?embedded_page.polling_timeout?>` s |

