---
icon: lucide/user-round
---

# Doctors

Register the doctor once and reuse the returned `id` as `doctor_id` in subsequent [sessions](embedded-page.md) or [visits](visit.md). Linking encounters to a doctor lets you track usage per clinician and view per-doctor analytics in the admin panel.

## Create a doctor

```
POST /doctors
Authorization: <api_key>
```

```json
{
  "last_name":  "Smith",
  "first_name": "Jane",
  "patronymic": null
}
```

**201 Created**

```json
{
  "id":         "d4c8b1a2-...",
  "last_name":  "Smith",
  "first_name": "Jane",
  "patronymic": null
}
```

| Field | Requirement |
|---|---|
| `last_name` | **Required.** |
| `first_name` | **Required.** |
| `patronymic` | Optional. |
