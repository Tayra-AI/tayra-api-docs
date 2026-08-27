---
icon: lucide/app-window
---

# Embedded Page Integration Guide

!!! warning "Placeholders"

    Values shown in `{{MONOSPACE}}` are confirmed at setup and are not yet fixed.

### Prerequisites

1. Obtain your API key from the Tayra team during onboarding. Send as `Authorization: Bearer <api_key>`. Store server-side only.
2. Allow outbound HTTPS and WSS (port 443) from clinician machines to `{{API_BASE_URL}}` and `{{APP_BASE_URL}}`. All connections are outbound. If an L7 proxy inspects traffic, ensure it permits the `Upgrade: websocket` header for these hosts.

## Integration flow

Tayra runs as an embedded view inside your MIS. For each encounter you create a session over HTTPS, supplying the note structure you need. You receive a URL to embed. The clinician records and reviews the consultation inside that view. You long-poll the session status until it reaches `completed`, then write the note into the patient chart.

**Everything is ordinary outbound HTTPS.** No inbound access, no open ports, no persistent protocol.

1. **Create a session** — one POST returns a `session_id` and a launch URL (the ID is a cryptographically random token — safe to pass to the workstation, no other credentials needed there)
2. **Embed the view** — load the URL in an iframe or WebView with microphone permission
3. **Poll for the note** — `GET` returns the session status: `in_progress`, `interrupted` (with partial content if the connection dropped), or `completed` with the final note

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
  loop until status = completed or 404
    E->>T: GET /v1/sessions/{id} (held up to <?embedded_page.polling_timeout?> s)
    T-->>E: 200 {status}
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
  style="width:100%;min-width:640px;height:100%;min-height:720px;border:0">
</iframe>
```

| Requirement | Detail |
|---|---|
| **Load promptly** | Single-use token, valid **<?embedded_page.launch_token_ttl?> seconds**. Expired → `launch_token_expired`. Already used → `launch_token_consumed`. |
| **Do not reload** | Reloading or unmounting invalidates the view. Use `relaunch` for a fresh URL. |
| **Microphone** | iframe: `allow="microphone"`. Desktop WebView: grant via host permission API. |
| **Size** | Recommended 640 × 720 CSS pixels or above (half of a 1280×720 HD screen, split side-by-side). Responsive to any container size. |

### 🚧 Relaunch

!!! danger "Under construction"

    This endpoint is designed and contracted but not yet live. You can build against the spec now — we'll notify you when it ships.

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

Long-poll the session. The response always includes a `status` field indicating the current state.

```
GET /v1/sessions/{session_id}?wait=<?embedded_page.polling_timeout?>
```

Recording in progress — **200 OK**

```json
{ "status": "in_progress" }
```

Connection interrupted, partial result available — **200 OK**

```json
{
  "status":  "interrupted",
  "content": {...}
}
```

If the clinician reconnects, status returns to `in_progress`.

Clinician submitted — **200 OK**

```json
{ 
  "status":       "completed",
  "content":      {...},
  "duration_s":   754,
  "finalized_at": "2026-08-12T09:32:04Z"
}
```

Session cancelled or expired — **404 Not Found**

```json
{ "error": { "code": "session_not_found" } }
```

### Loop mechanics

1. Send `GET` with `wait`. Server holds the connection until a status transition or timeout.
2. `status: "in_progress"` — no change yet; re-poll.
3. `status: "interrupted"` — connection dropped; `content` contains the partial result. Decide whether to wait for reconnection or persist as-is.
4. `status: "completed"` — final note ready; persist to chart and stop polling.
5. `404` — session cancelled or expired; stop polling.

### Practical notes

| Concern | What to do |
|---|---|
| **Client timeout** | Set above the wait — e.g. `<?embedded_page.polling_timeout?>` + 15 s. |
| **Short-polling fallback** | If long-polling is not feasible, omit `wait` and poll every `<?embedded_page.min_poll_s?>` s. Long-polling is recommended. |
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
| `launch_token_expired` | 410 | URL older than <?embedded_page.launch_token_ttl?> s. Call `relaunch`. |
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
| Launch URL validity | <?embedded_page.launch_token_ttl?> s, single use |
| Max consultation length | `{{MAX_DURATION}}` |
| Inactivity timeout | `{{IDLE_TIMEOUT}}` |
| Note retention | `{{NOTE_RETENTION}}` |
| Max long-poll wait | `<?embedded_page.polling_timeout?>` s |
| Min short-poll interval | `<?embedded_page.min_poll_s?>` s |

