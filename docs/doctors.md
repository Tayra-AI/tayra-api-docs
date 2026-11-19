---
icon: lucide/user-round
---

# Doctors

Register the doctor once, then link [sessions](embedded-page.md) and [visits](visit.md) to them. Linking encounters to a doctor lets you track usage per clinician and view per-doctor analytics in the admin panel.

A doctor can be referenced in two ways:

- by our `id`, returned at registration, passed as `doctor_id`;
- by your own MIS identifier, set as `external_id` at registration, passed as `doctor_external_id`. You don't need to store our `id` then.

## Create a doctor

```
POST /doctors
Authorization: <api_key>
```

```json
{
  "external_id": "mis-doctor-1042",
  "last_name":   "Smith",
  "first_name":  "Jane",
  "patronymic":  null
}
```

**201 Created**

```json
{
  "id":          "d4c8b1a2-...",
  "external_id": "mis-doctor-1042",
  "last_name":   "Smith",
  "first_name":  "Jane",
  "patronymic":  null
}
```

| Field | Requirement |
|---|---|
| `external_id` | Optional. The doctor's identifier in your MIS, 1–255 characters, unique within the clinic. |
| `last_name` | **Required.** |
| `first_name` | **Required.** |
| `patronymic` | Optional. |

If the clinic already has a doctor with this `external_id`, no new doctor is created: the existing one is returned unchanged with **200 OK**. Registration with `external_id` is therefore safe to repeat. To change a doctor's name, use [Update a doctor](#update-a-doctor).

Without `external_id`, every call creates a new doctor and `external_id` is `null` in the response.

## Update a doctor

```
PATCH /doctors/{id}
Authorization: <api_key>
```

```json
{
  "external_id": "mis-doctor-1042"
}
```

**200 OK** — the updated doctor, in the same format as on creation.

Only the fields you send are changed. Use it to correct a name or to set the MIS identifier of a doctor registered without one; `"external_id": null` removes it.

| Field | Requirement |
|---|---|
| `external_id` | Optional. 1–255 characters, or `null`. |
| `last_name` | Optional. Cannot be `null`. |
| `first_name` | Optional. Cannot be `null`. |
| `patronymic` | Optional. |

| Status | When |
|---|---|
| `404` | The doctor does not exist in your clinic. |
| `409` | Another doctor of the clinic already has this `external_id`. |

## Link a session or visit to a doctor

`POST /sessions`, `POST /visits` and `POST /visits/custom` accept either field:

```json
{ "doctor_external_id": "mis-doctor-1042" }
```

```json
{ "doctor_id": "d4c8b1a2-..." }
```

| Case | Result |
|---|---|
| `doctor_id` or `doctor_external_id` | The encounter is linked to that doctor. |
| Both fields | `422` |
| Unknown doctor | `404` |
| Neither field | The encounter is not linked to a specific doctor and is not included in per-doctor analytics. |
