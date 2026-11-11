# Notly Watch ↔ Notly phone protocol (v1)

Transport: Wearable Data Layer (Google Play services). Both apps **must** share the same
`applicationId` (incl. flavor suffix: `com.notly.app`, `.test`, `.uat`) and be signed with the
same key, otherwise Play services will not route anything between them.

Code copies (keep in sync):
- Watch: `wear/src/main/java/dev/tberghuis/voicememos/sync/NotlyProtocol.kt`
- Phone: `notly-mobile-app/android/app/src/main/kotlin/com/notly/app/watch/WatchProtocol.kt`

## Capabilities (discovery)
| Capability    | Declared by | Used for |
|---------------|-------------|----------|
| `notly_wear`  | watch app   | phone detects "Notly Watch installed on connected watch" |
| `notly_phone` | phone app   | watch finds the phone to send to; CAPABILITY_CHANGED resumes queued transfers |

## Authorisation (phone → watch, RPC `MessageClient.sendRequest`)
`/notly/v1/authorize`
```json
{ "token": "<32 random bytes, base64url>",
  "doctor": { "publicId": "...", "name": "...", "clinic": "...", "specialty": "..." } }
```
→ `{ "status": "ok", "deviceName": "..." }`

The doctor confirms with biometrics on the phone first. The token is a *companion credential*
scoped to that watch node + doctor; the Notly API token / password never reaches the watch.

`/notly/v1/revoke` → `{ "status": "ok" }` (watch forgets credential, keeps queued recordings)
`/notly/v1/status` → `{ "status":"ok", "authorized":bool, "doctorPublicId", "deviceName", "recording":bool, "pendingCount":int }`

## Recording transfer (watch → phone), per segment
A consultation = consent timestamp + 1..n AAC `.m4a` segments (n > 1 when "Continue recording"
is used). Each segment has a UUID `recordingId`.

1. **Offer** – RPC `/notly/v1/offer`
   ```json
   { "token", "doctorPublicId", "consultationId", "createdAt", "consentAt", "segmentCount",
     "segment": { "index", "recordingId", "durationMs", "sizeBytes", "sha256" } }
   ```
   → `send` | `have` (already received/imported → treat as delivered) | `unauthorized` | `error`
2. **File** – `ChannelClient` channel on path `/notly/v1/file/<recordingId>`; the watch streams the
   file and closes its output. The phone receives it with `receiveFile` into its inbox.
3. **Verify** – RPC `/notly/v1/verify` `{ "recordingId" }`
   → `ok` (size + SHA-256 match, durably stored) | `pending` (still writing, ask again) |
   `corrupt` / `missing` (watch re-sends on next attempt)

Only `ok`/`have` marks a segment delivered on the watch. Audio is deleted from the watch only after
**every** segment of the consultation is delivered.

## Reliability
- Watch: WorkManager `TransferWorker` (unique work `notly_transfer`, exponential backoff from 30 s,
  foreground `dataSync`), re-enqueued on app start, on `notly_phone` CAPABILITY_CHANGED and on
  re-authorisation. State per consultation is persisted in `consultation.json`.
- Phone: inbox survives app restarts; consultations appear to Flutter only once complete;
  `markImported` deletes the plaintext copy and remembers ids so retries are idempotent.
- Duplicates: phone answers `have` for any recordingId already received or imported; Flutter uses
  the watch `consultationId` as the pending consultation `localId` and skips segments already
  present in recording metadata.
