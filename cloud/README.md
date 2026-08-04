# Chirp private cloud

This folder is the private Google Cloud backend for Chirp. It keeps Google credentials off the phone and gives the Android app a durable upload and transcription job.

The service does four jobs.

1. It checks a Firebase ID token and allows one configured Firebase UID.
2. It creates resumable Cloud Storage uploads for finalized audio files.
3. It runs Cloud Speech-to-Text V2 with `chirp_3` and keeps job state in Firestore.
4. It exposes Vertex Gemini text generation for Chirp's resolved enhancement prompts.

The backend never intentionally logs auth tokens, upload session URLs, audio, transcripts, prompts, or generated text. Cloud Run still writes its normal request metadata logs.

## Files

- `app.py` has the Flask API and small Google Cloud adapters.
- `test_app.py` checks the API with in-memory fakes.
- `Dockerfile` runs the API through Gunicorn on Cloud Run.
- `.gcloudignore` keeps tests, docs, local config, and caches out of source builds.
- `bucket-lifecycle.json` deletes input audio after 30 days and Speech result objects after 31 days.
- `scripts/bootstrap.sh` creates the GCP resources, enables Firestore TTL, and applies IAM bindings.
- `scripts/deploy.sh` deploys the Cloud Run service.

## Data flow

```text
Android saves audio locally
  -> Cloud Run creates a Firestore job and GCS resumable session
  -> Android uploads directly to the private GCS bucket
  -> Cloud Run checks byte length, content type, and CRC32C
  -> Speech V2 BatchRecognize writes its result back to GCS
  -> Android WorkManager polls Cloud Run
  -> Cloud Run saves the literal transcript in Firestore
  -> Android posts a ready notification and keeps the local audio
```

The app keeps the source recording. Cloud storage is a processing copy and short-term backup, not the only copy.

## Authentication

Cloud Run has an unauthenticated IAM invoker because a mobile user's Firebase token is not a Cloud Run IAM identity. Every `/v1/*` route still requires this header.

```http
Authorization: Bearer FIREBASE_ID_TOKEN
```

The backend verifies the token with Firebase Admin, checks revocation, requires `email_verified == true`, and compares the token's `uid` with `ALLOWED_FIREBASE_UID`. `/healthz` is the only route that skips app authentication.

Missing, invalid, expired, or revoked tokens return HTTP 401. A valid user outside the allowlist or without a verified email returns HTTP 403. If Firebase token verification itself is unavailable, the API returns HTTP 503 so the app can retry instead of treating an outage as a sign-out.

No service-account key belongs in the APK or this repository. Cloud Run uses its attached `chirp-api` service account through Application Default Credentials.

## Dictation API

### Create or resume a job

```http
POST /v1/dictations
Authorization: Bearer FIREBASE_ID_TOKEN
Idempotency-Key: STABLE_LOCAL_RECORDING_UUID
Content-Type: application/json
```

```json
{
  "contentType": "audio/mp4",
  "byteLength": 1234567,
  "durationMs": 42000,
  "crc32c": "base64-crc32c",
  "languageCode": "en-US",
  "cleanup": false
}
```

`contentType`, `byteLength`, `durationMs`, and `crc32c` are required. `languageCode` defaults to `en-US`. `cleanup` must be `false`. Server-side dictation cleanup is not a second hidden state machine, so enhancement runs through `/v1/text:generate` after the literal transcript is safe.

The service accepts `audio/mp4`, `audio/wav`, and `audio/mpeg`, with a 256 MiB upload cap. That leaves plenty of room for one hour of Chirp's mono PCM WAV while limiting storage damage from a bad or stolen user token. Chirp's current M4A output uses `audio/mp4` and Speech V2 auto-detects its AAC encoding.

A new job returns HTTP 201. Reusing the same idempotency key and the same immutable audio metadata returns the same job with HTTP 200.

```json
{
  "job": {
    "id": "32-hex-id",
    "state": "AWAITING_UPLOAD",
    "createdAt": "2026-07-27T00:00:00Z",
    "updatedAt": "2026-07-27T00:00:00Z",
    "contentType": "audio/mp4",
    "byteLength": 1234567,
    "durationMs": 42000,
    "languageCode": "en-US",
    "cleanupRequested": false,
    "transcript": null,
    "rawTranscript": null,
    "polishedTranscript": null,
    "error": null
  },
  "upload": {
    "sessionUrl": "https://storage.googleapis.com/upload/...",
    "method": "PUT",
    "expiresInSeconds": 604800,
    "chunkSizeBytes": 8388608,
    "contentType": "audio/mp4",
    "byteLength": 1234567,
    "crc32c": "base64-crc32c"
  }
}
```

`upload` is `null` when GCS already has the complete object or the job has moved past upload.

Treat `sessionUrl` like a password. Save it only in Chirp's private local job state and never log it. It works without another auth header and expires after one week.

For a one-shot upload, send this request straight to `sessionUrl`.

```http
PUT SESSION_URL
Content-Length: TOTAL_BYTES
X-Goog-Hash: crc32c=BASE64_CRC32C
```

For chunks, add `Content-Range: bytes START-END/TOTAL`. GCS returns HTTP 308 with its persisted byte range until the final request returns 200 or 201. The last chunk needs `X-Goog-Hash` for whole-object server-side validation. Use an empty PUT with `Content-Length: 0` and `Content-Range: bytes */TOTAL` to ask GCS how much it has.

### Commit the upload

```http
POST /v1/dictations/JOB_ID/commit
Authorization: Bearer FIREBASE_ID_TOKEN
```

This checks the finalized GCS object's byte length, CRC32C, and content type. A valid object starts Speech and returns HTTP 202 with `state: TRANSCRIBING`. Calling commit again is safe. An unfinished upload returns HTTP 409 with `error.code: upload_incomplete`.

### Poll the job

```http
GET /v1/dictations/JOB_ID
Authorization: Bearer FIREBASE_ID_TOKEN
```

This returns `{"job": ...}` and checks the Speech operation. Android should call it from durable WorkManager, not an IME coroutine. The response uses `Cache-Control: no-store`.

Public states are `AWAITING_UPLOAD`, `TRANSCRIBING`, `READY`, `FAILED`, and `DELETING`. `READY` and `FAILED` are terminal transcription states. `DELETING` is a tombstone while an active Speech operation is being stopped. `transcript` prefers `polishedTranscript` when one exists and otherwise returns `rawTranscript`.

If Speech finishes but its result object is missing, the service checks that the source audio still exists and saves a retryable `speech_result_missing` failure. That transition only claims `TRANSCRIBING`, so a racing `DELETING` tombstone always wins.

Firestore gives each Speech submission one owner through a random lease. Concurrent commit calls can see `TRANSCRIBING`, but only the lease owner calls Speech. If the service dies after Speech accepts a request and before its operation name reaches Firestore, a later call searches Speech operations for the exact input and output URIs. It records the recovered operation before polling. It only submits again after the lease is stale and that search confirms no matching operation exists.

### Retry or delete

```http
POST /v1/dictations/JOB_ID/retry
DELETE /v1/dictations/JOB_ID
```

Retry reuses a valid cloud audio object or returns a fresh resumable upload.

Delete first writes a `DELETING` tombstone. If Speech is active, the service cancels the operation and returns HTTP 202 until Speech reports that it has stopped. Call `DELETE` again, or poll `GET`, until deletion ends with HTTP 204 or HTTP 404. The service sweeps source and result objects again after Speech stops, then deletes the Firestore job. This keeps a late Speech result from surviving a delete race.

The service keeps the current GCS resumable upload session URI in the private Firestore job. Delete cancels that session before removing objects. It also deletes every Vertex generation record atomically linked to the recording before removing the dictation job.

Bucket soft delete and Object Versioning are disabled, so an API deletion does not leave a hidden readable copy. The 31-day `results/` lifecycle keeps a finished Speech result available through the Firestore job's full 30-day window, even when the phone stays offline, and deletes an orphaned result one day later.

Android does not call the cloud delete endpoint yet when a local recording is deleted. Every Firestore job has an `expiresAt` timestamp 30 days after creation, and the bootstrap enables a TTL policy on that field. Firestore deletes expired jobs asynchronously, so this is a bounded-retention fallback rather than an immediate local-delete hook.

## Vertex text generation

Chirp sends its already-resolved mode prompt and a transcript here.

```http
POST /v1/text:generate
Authorization: Bearer FIREBASE_ID_TOKEN
Idempotency-Key: STABLE_GENERATION_REQUEST_KEY
Recording-Id: STABLE_LOCAL_RECORDING_UUID
Content-Type: application/json
```

```json
{
  "text": "literal transcript",
  "prompt": "fully resolved mode prompt",
  "model": "gemini-3.6-flash"
}
```

`model` is optional and defaults to `GEMINI_MODEL`. `Idempotency-Key` and `Recording-Id` are both required and must be 8 to 128 characters. `Recording-Id` is the same local recording identifier used as the dictation idempotency key. The backend derives the private dictation ID from it and only creates a generation record when that owned dictation is `READY`. Chirp includes the recording identifier and exact request body in its SHA-256 idempotency key, so the same queued work keeps the same key across process restarts and identical text from different recordings cannot collide.

```json
{
  "text": "generated result",
  "model": "gemini-3.6-flash"
}
```

Before calling Vertex, one Firestore transaction creates the generation record and links its ID to the owned dictation. It stores only a request hash, not the source text or prompt. The completed text and model are saved before HTTP 200 is returned. Repeating the same key and request returns that saved response without another Vertex call. Reusing the key with different text, prompt, or model returns HTTP 409 with `idempotency_conflict`. A recording can have at most 20 generation records.

A concurrent request returns HTTP 202 with `state: GENERATING`. An uncertain provider failure, such as a timeout after Vertex may have accepted the request, leaves that lease in `GENERATING`. Immediate retries return HTTP 202 and do not call Vertex again. The same request can claim the lease and try once more after 10 minutes, which keeps recovery bounded while putting a delay between possibly duplicate billable calls. A definite 4xx rejection is saved as `FAILED`; non-retryable rejections never call Vertex again with that key.

Generation records use the same 30-day Firestore TTL as dictation jobs. The response uses `Cache-Control: no-store`. A Vertex failure returns a generic error and does not echo the text or prompt. The Gemini SDK request gets 240 seconds and caps output at 65,536 tokens. Gunicorn and Cloud Run each allow 300 seconds, so clients should allow at least 330 seconds for this route.

## Daily work caps

The service keeps per-user UTC-day counters in Firestore. New work stops before the Google API call when a counter is full. Idempotent replays do not count again. The defaults are 50 new dictations, 2 GiB of audio, 12 hours of declared audio duration, 75 Speech submissions, and 200 Vertex requests per day. A recording can have at most 20 linked Vertex generation records.

These are application caps, not a Google Cloud billing hard stop. They bound what the one allowed Firebase account can start through this service, including if its token is stolen. Change them with `DAILY_DICTATION_LIMIT`, `DAILY_AUDIO_BYTES_LIMIT`, `DAILY_AUDIO_DURATION_MS_LIMIT`, `DAILY_SPEECH_SUBMISSION_LIMIT`, and `DAILY_VERTEX_REQUEST_LIMIT`.

## Local tests

The test suite never contacts Firebase or GCP.

```bash
cd cloud
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python -m unittest -v
```

For a live local run, set all deployment environment variables and run this only after Application Default Credentials exist.

```bash
gunicorn --bind :8080 "app:create_app()"
```

## Google Cloud handoff

Nothing in this folder needs a credential to build or test. The first live command needs Harsha's GCP authentication.

The needed inputs are

- A GCP project ID attached to the billing account that owns the credits
- A Google Cloud login allowed to enable APIs, create the service account, create Firestore and Storage resources, change IAM, build, and deploy Cloud Run
- The Google account that will be the only allowed app user

The original private-build key is restored at `~/.android/debug.keystore`. Register `dev.chirpboard.app` in Firebase with its SHA-1, `45:D6:44:7D:26:7C:1D:42:0A:7B:1E:4D:59:4C:75:E9:47:75:DA:52`. The bucket name can be picked from the project ID during provisioning. Set up Firebase in the same GCP project, enable the Google sign-in provider, download the `google-services.json` supplied by the Android app registration, and sign in once to get the Firebase UID. The API does not use the Google account email as its authorization key.

Build the Firebase sign-in APK before deploying the API. The first phone sign-in creates the Firebase user and gives the deploy script its `ALLOWED_FIREBASE_UID`. Deploy the API with that UID, put the returned Cloud Run HTTPS URL in the private `CHIRP_CLOUD_BASE_URL` Gradle property, and rebuild the final APK.

These are the first commands that cross the auth boundary. They have not been run by this scaffold.

```bash
gcloud auth login
gcloud auth application-default login
gcloud config set project PROJECT_ID
```

The bootstrap also needs the small gcloud beta command component for Google's managed Speech identity. It is already installed on this Mac. Firebase CLI 15.24.0 is installed too, but has not been signed in. Install the beta component before provisioning on another machine if `gcloud components list --filter=id:beta` shows `Not Installed`.

```bash
gcloud components install beta
```

Application Default Credentials are only needed for live local calls. Cloud Run gets credentials from its attached service account.

After authentication, provision the dedicated project resources.

```bash
cd cloud
export PROJECT_ID="your-billing-linked-project"
export GCS_BUCKET="your-globally-unique-private-bucket"
./scripts/bootstrap.sh
```

The bootstrap creates a dedicated US multi-region bucket and labels it `chirp_private_api=true`. It refuses to change an existing unlabeled bucket. If the named bucket is already dedicated to Chirp, claim it once with `ALLOW_EXISTING_CHIRP_BUCKET=true`. The script also stops if the default Firestore database exists in Datastore mode, since this service needs Firestore Native mode.

Then deploy.

```bash
export ALLOWED_FIREBASE_UID="the-single-firebase-uid"
export GEMINI_MODEL="gemini-3.6-flash"
./scripts/deploy.sh
```

The runtime service account gets these roles.

| Scope | Role | Use |
| --- | --- | --- |
| Project | `roles/speech.client` | Submit and poll Speech V2 |
| Project | `projects/PROJECT_ID/roles/chirpSpeechCanceller` | Cancel a Speech operation while deleting its job |
| Project | `roles/datastore.user` | Read and write Firestore jobs |
| Project | `roles/firebaseauth.viewer` | Check revoked or disabled Firebase users |
| Project | `roles/aiplatform.user` | Call Vertex Gemini |
| Bucket | `roles/storage.objectUser` | Start uploads, check objects, read results, and delete job data |

Source builds use a separate `chirp-build` service account with `roles/run.builder`. The build account is not the runtime identity and does not get access to Firestore, audio, Speech, or Vertex.

The bootstrap also makes sure Google's managed Speech identity has `roles/speech.serviceAgent` in this dedicated project. That role lets Speech read the input object and write its result. It belongs only on `service-PROJECT_NUMBER@gcp-sa-speech.iam.gserviceaccount.com`, never on the Cloud Run service account.

The bootstrap enforces uniform bucket-level access, public access prevention, disabled soft delete, and disabled Object Versioning. Its lifecycle deletes `audio/` objects after 30 days and `results/` objects after 31 days. Firestore TTL makes each `dictations` job eligible for deletion 30 days after creation, including its transcript. Firestore applies TTL asynchronously. If the installed `gcloud` CLI does not have the Firestore TTL command, the bootstrap prints a warning and the `dictations.expiresAt` TTL policy must be enabled manually. Do not enable Speech data logging for this project.

## Current boundary

Chirp 3 batch transcription supports recordings up to one hour in this service. Android pins a longer or oversized row to local Parakeet before making a cloud request. Supporting cloud transcription for longer recordings means uploading ordered sub-hour parts and stitching their transcripts. That is intentionally outside v1.
