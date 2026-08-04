from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import secrets
from datetime import datetime, timedelta, timezone
from urllib.parse import quote, urlparse

from flask import Flask, g, jsonify, request


CONTENT_TYPES = {
    "audio/mp4": "m4a",
    "audio/wav": "wav",
    "audio/mpeg": "mp3",
}
PUBLIC_STATES = {
    "SUBMITTING": "TRANSCRIBING",
}
UPLOAD_CHUNK_SIZE = 8 * 1024 * 1024
UPLOAD_SESSION_SECONDS = 7 * 24 * 60 * 60
UPLOAD_SESSION_REUSE_SECONDS = 6 * 24 * 60 * 60
MAX_DURATION_MS = 60 * 60 * 1000
MAX_AUDIO_BYTES = 256 * 1024 * 1024
STALE_SUBMIT_SECONDS = 2 * 60
STALE_GENERATION_SECONDS = 10 * 60
MAX_TEXT_CHARS = 250_000
MAX_PROMPT_CHARS = 30_000
GEMINI_TIMEOUT_MS = 4 * 60 * 1000
GEMINI_MAX_OUTPUT_TOKENS = 65_536
JOB_RETENTION_DAYS = 30
USAGE_RETENTION_DAYS = 3
MAX_GENERATIONS_PER_RECORDING = 20
DEFAULT_DAILY_DICTATION_LIMIT = 50
DEFAULT_DAILY_AUDIO_BYTES_LIMIT = 2 * 1024 * 1024 * 1024
DEFAULT_DAILY_AUDIO_DURATION_MS_LIMIT = 12 * 60 * 60 * 1000
DEFAULT_DAILY_SPEECH_SUBMISSION_LIMIT = 75
DEFAULT_DAILY_VERTEX_REQUEST_LIMIT = 200
LANGUAGE_CODE = re.compile(r"^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")
MODEL_NAME = re.compile(r"^[A-Za-z0-9._-]+$")
DICTATION_ID = re.compile(r"^[a-f0-9]{32}$")


class InvalidAuthToken(Exception):
    pass


def utc_now(now: datetime | None = None) -> str:
    return (now or datetime.now(timezone.utc)).isoformat().replace("+00:00", "Z")


def retention_deadline(now: datetime | None = None) -> datetime:
    return (now or datetime.now(timezone.utc)) + timedelta(days=JOB_RETENTION_DAYS)


def is_stale(timestamp: str | None, seconds: int = STALE_SUBMIT_SECONDS) -> bool:
    if not timestamp:
        return True
    try:
        started = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    except (AttributeError, ValueError):
        return True
    return (datetime.now(timezone.utc) - started).total_seconds() >= seconds


def positive_int_env(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        parsed = int(value)
    except ValueError as error:
        raise RuntimeError(f"{name} must be a positive integer") from error
    if parsed <= 0:
        raise RuntimeError(f"{name} must be a positive integer")
    return parsed


def configured_limit(config: dict, name: str, default: int) -> int:
    value = config.get(name, default)
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise RuntimeError(f"{name} must be a positive integer")
    return value


def provider_http_status(error: Exception) -> int | None:
    for value in (
        getattr(error, "status_code", None),
        getattr(error, "http_status_code", None),
        getattr(error, "code", None),
        getattr(getattr(error, "response", None), "status_code", None),
    ):
        if isinstance(value, int) and not isinstance(value, bool):
            return value
    return None


def deterministic_provider_failure(error: Exception) -> dict | None:
    status = provider_http_status(error)
    if isinstance(error, (TypeError, ValueError)):
        retryable = False
    elif status is not None and 400 <= status < 500 and status != 408:
        retryable = status in {409, 429}
    else:
        return None
    return {
        "code": "provider_request_rejected",
        "message": "The cloud provider rejected the request.",
        "retryable": retryable,
    }


def batch_result_uri(file_result) -> str:
    cloud_result = getattr(file_result, "cloud_storage_result", None)
    return (
        getattr(cloud_result, "native_format_uri", "")
        or getattr(cloud_result, "uri", "")
        or getattr(file_result, "uri", "")
    )


def load_config() -> dict:
    required = ["GCP_PROJECT", "GCS_BUCKET", "ALLOWED_FIREBASE_UID"]
    missing = [name for name in required if not os.getenv(name)]
    if missing:
        raise RuntimeError(f"Missing environment variables: {', '.join(missing)}")
    return {
        "project": os.environ["GCP_PROJECT"],
        "bucket": os.environ["GCS_BUCKET"],
        "allowed_uid": os.environ["ALLOWED_FIREBASE_UID"],
        "speech_location": os.getenv("SPEECH_LOCATION", "us"),
        "firestore_collection": os.getenv("FIRESTORE_COLLECTION", "dictations"),
        "vertex_enabled": os.getenv("VERTEX_ENABLED", "true").lower() == "true",
        "vertex_location": os.getenv("VERTEX_LOCATION", "global"),
        "gemini_model": os.getenv("GEMINI_MODEL", "gemini-3.6-flash"),
        "daily_dictation_limit": positive_int_env(
            "DAILY_DICTATION_LIMIT", DEFAULT_DAILY_DICTATION_LIMIT
        ),
        "daily_audio_bytes_limit": positive_int_env(
            "DAILY_AUDIO_BYTES_LIMIT", DEFAULT_DAILY_AUDIO_BYTES_LIMIT
        ),
        "daily_audio_duration_ms_limit": positive_int_env(
            "DAILY_AUDIO_DURATION_MS_LIMIT",
            DEFAULT_DAILY_AUDIO_DURATION_MS_LIMIT,
        ),
        "daily_speech_submission_limit": positive_int_env(
            "DAILY_SPEECH_SUBMISSION_LIMIT",
            DEFAULT_DAILY_SPEECH_SUBMISSION_LIMIT,
        ),
        "daily_vertex_request_limit": positive_int_env(
            "DAILY_VERTEX_REQUEST_LIMIT", DEFAULT_DAILY_VERTEX_REQUEST_LIMIT
        ),
    }


class FirestoreJobs:
    def __init__(self, client, collection: str):
        self.client = client
        self.collection = client.collection(collection)

    def create(self, job: dict) -> tuple[dict, bool]:
        from google.api_core.exceptions import AlreadyExists

        document = self.collection.document(job["id"])
        try:
            document.create(job)
            return job, True
        except AlreadyExists:
            return document.get().to_dict(), False

    def create_dictation(
        self,
        job: dict,
        increments: dict[str, int],
        limits: dict[str, int],
    ) -> tuple[dict | None, bool, str | None]:
        from google.cloud import firestore

        document = self.collection.document(job["id"])
        day = job["createdAt"][:10]
        usage_id = hashlib.sha256(f"usage:{job['uid']}:{day}".encode()).hexdigest()[:32]
        usage_document = self.collection.document(f"usage-{usage_id}")
        transaction = self.client.transaction()

        @firestore.transactional
        def apply(transaction):
            snapshot = document.get(transaction=transaction)
            if snapshot.exists:
                return snapshot.to_dict(), False, None
            usage_snapshot = usage_document.get(transaction=transaction)
            usage = usage_snapshot.to_dict() if usage_snapshot.exists else {}
            if any(usage.get(name, 0) + amount > limits[name] for name, amount in increments.items()):
                return None, False, "daily_limit"
            now = datetime.now(timezone.utc)
            usage.update(
                {
                    "id": f"usage-{usage_id}",
                    "uid": job["uid"],
                    "kind": "DAILY_USAGE",
                    "day": day,
                    "updatedAt": utc_now(now),
                    "expiresAt": now + timedelta(days=USAGE_RETENTION_DAYS),
                }
            )
            for name, amount in increments.items():
                usage[name] = usage.get(name, 0) + amount
            transaction.create(document, job)
            transaction.set(usage_document, usage)
            return job, True, None

        return apply(transaction)

    def create_generation(
        self,
        generation: dict,
        dictation_id: str,
    ) -> tuple[dict | None, bool, str | None]:
        from google.cloud import firestore

        generation_document = self.collection.document(generation["id"])
        dictation_document = self.collection.document(dictation_id)
        transaction = self.client.transaction()

        @firestore.transactional
        def apply(transaction):
            generation_snapshot = generation_document.get(transaction=transaction)
            dictation_snapshot = dictation_document.get(transaction=transaction)
            if not dictation_snapshot.exists:
                return None, False, "not_found"
            dictation = dictation_snapshot.to_dict()
            if dictation.get("uid") != generation["uid"]:
                return None, False, "not_found"
            if dictation.get("state") == "DELETING":
                return None, False, "deleting"
            if dictation.get("state") != "READY":
                return None, False, "not_ready"
            if generation_snapshot.exists:
                return generation_snapshot.to_dict(), False, None
            generation_ids = dictation.get("generationIds") or []
            if len(generation_ids) >= MAX_GENERATIONS_PER_RECORDING:
                return None, False, "generation_limit"
            transaction.create(generation_document, generation)
            transaction.update(
                dictation_document,
                {"generationIds": firestore.ArrayUnion([generation["id"]])},
            )
            return generation, True, None

        return apply(transaction)

    def reserve_usage(
        self,
        uid: str,
        increments: dict[str, int],
        limits: dict[str, int],
    ) -> bool:
        from google.cloud import firestore

        now = datetime.now(timezone.utc)
        day = now.date().isoformat()
        usage_id = hashlib.sha256(f"usage:{uid}:{day}".encode()).hexdigest()[:32]
        document = self.collection.document(f"usage-{usage_id}")
        transaction = self.client.transaction()

        @firestore.transactional
        def apply(transaction):
            snapshot = document.get(transaction=transaction)
            usage = snapshot.to_dict() if snapshot.exists else {}
            if any(usage.get(name, 0) + amount > limits[name] for name, amount in increments.items()):
                return False
            usage.update(
                {
                    "id": f"usage-{usage_id}",
                    "uid": uid,
                    "kind": "DAILY_USAGE",
                    "day": day,
                    "updatedAt": utc_now(now),
                    "expiresAt": now + timedelta(days=USAGE_RETENTION_DAYS),
                }
            )
            for name, amount in increments.items():
                usage[name] = usage.get(name, 0) + amount
            transaction.set(document, usage)
            return True

        return apply(transaction)

    def get(self, job_id: str) -> dict | None:
        snapshot = self.collection.document(job_id).get()
        return snapshot.to_dict() if snapshot.exists else None

    def patch(self, job_id: str, values: dict) -> dict:
        document = self.collection.document(job_id)
        document.update(values)
        return document.get().to_dict()

    def claim(
        self,
        job_id: str,
        states: set[str],
        values: dict,
        expected: dict | None = None,
    ) -> tuple[dict | None, bool]:
        from google.cloud import firestore

        document = self.collection.document(job_id)
        transaction = self.client.transaction()

        @firestore.transactional
        def apply(transaction):
            snapshot = document.get(transaction=transaction)
            if not snapshot.exists:
                return None, False
            job = snapshot.to_dict()
            matches = all(job.get(key) == value for key, value in (expected or {}).items())
            won = job["state"] in states and matches
            if won:
                transaction.update(document, values)
                job.update(values)
            return job, won

        return apply(transaction)

    def delete(self, job_id: str) -> None:
        self.collection.document(job_id).delete()

    def delete_many(self, job_ids: list[str]) -> None:
        for start in range(0, len(job_ids), 500):
            batch = self.client.batch()
            for job_id in job_ids[start : start + 500]:
                batch.delete(self.collection.document(job_id))
            batch.commit()


class GoogleStorage:
    def __init__(self, project: str, bucket_name: str):
        import google.auth
        from google.auth.transport.requests import AuthorizedSession
        from google.cloud import storage

        credentials, _ = google.auth.default(
            scopes=["https://www.googleapis.com/auth/devstorage.read_write"]
        )
        self.bucket_name = bucket_name
        self.client = storage.Client(project=project, credentials=credentials)
        self.bucket = self.client.bucket(bucket_name)
        self.upload_http = AuthorizedSession(credentials)

    def create_upload_session(self, job: dict) -> str:
        object_name = quote(job["objectName"], safe="")
        bucket_name = quote(self.bucket_name, safe="")
        url = (
            f"https://storage.googleapis.com/upload/storage/v1/b/{bucket_name}/o"
            f"?uploadType=resumable&name={object_name}&ifGenerationMatch=0"
        )
        response = self.upload_http.post(
            url,
            headers={
                "Content-Type": "application/json",
                "X-Upload-Content-Type": job["contentType"],
                "X-Upload-Content-Length": str(job["byteLength"]),
            },
            json={
                "contentType": job["contentType"],
                "metadata": {
                    "dictationId": job["id"],
                    "expectedCrc32c": job["crc32c"],
                },
            },
            timeout=30,
        )
        response.raise_for_status()
        return response.headers["Location"]

    def cancel_upload_session(self, session_url: str) -> None:
        parsed = urlparse(session_url)
        if (
            parsed.scheme != "https"
            or parsed.hostname != "storage.googleapis.com"
            or parsed.username is not None
            or parsed.password is not None
            or parsed.port not in {None, 443}
        ):
            raise ValueError("Unexpected Cloud Storage upload session URL")
        response = self.upload_http.delete(
            session_url,
            headers={"Content-Length": "0"},
            timeout=20,
        )
        if response.status_code not in {200, 204, 404, 410, 499}:
            response.raise_for_status()

    def object_info(self, object_name: str) -> dict | None:
        from google.api_core.exceptions import NotFound

        blob = self.bucket.blob(object_name)
        try:
            blob.reload(timeout=20)
        except NotFound:
            return None
        return {
            "size": int(blob.size),
            "crc32c": blob.crc32c,
            "contentType": blob.content_type,
        }

    def read_text(self, uri: str) -> str:
        prefix = f"gs://{self.bucket_name}/"
        if not uri.startswith(prefix):
            raise RuntimeError("Speech returned an unexpected output bucket")
        return self.bucket.blob(uri[len(prefix) :]).download_as_text(timeout=30)

    def delete_job_objects(self, job: dict) -> None:
        from google.api_core.exceptions import NotFound

        source = self.bucket.blob(job["objectName"])
        try:
            source.delete(timeout=20)
        except NotFound:
            pass
        for blob in self.bucket.list_blobs(
            prefix=f"results/{job['id']}/", timeout=20
        ):
            try:
                blob.delete(timeout=20)
            except NotFound:
                pass


class GoogleSpeech:
    def __init__(self, project: str, location: str, bucket: str, storage: GoogleStorage):
        from google.api_core.client_options import ClientOptions
        from google.cloud import speech_v2

        self.project = project
        self.location = location
        self.bucket = bucket
        self.storage = storage
        self.types = speech_v2.types.cloud_speech
        self.client = speech_v2.SpeechClient(
            client_options=ClientOptions(api_endpoint=f"{location}-speech.googleapis.com")
        )

    def submit(self, job: dict) -> str:
        speech = self.types
        input_uri = f"gs://{self.bucket}/{job['objectName']}"
        output_uri = f"gs://{self.bucket}/results/{job['id']}/"
        config = speech.RecognitionConfig(
            auto_decoding_config=speech.AutoDetectDecodingConfig(),
            language_codes=[job["languageCode"]],
            model="chirp_3",
            features=speech.RecognitionFeatures(
                enable_automatic_punctuation=True,
                profanity_filter=False,
            ),
        )
        operation = self.client.batch_recognize(
            request=speech.BatchRecognizeRequest(
                recognizer=(
                    f"projects/{self.project}/locations/{self.location}/recognizers/_"
                ),
                config=config,
                files=[speech.BatchRecognizeFileMetadata(uri=input_uri)],
                recognition_output_config=speech.RecognitionOutputConfig(
                    gcs_output_config=speech.GcsOutputConfig(uri=output_uri)
                ),
            ),
            timeout=30,
        )
        name = operation.operation.name
        return name.decode() if isinstance(name, bytes) else name

    def poll(self, job: dict) -> dict:
        operation = self.client.transport.operations_client.get_operation(
            job["operationName"], timeout=20
        )
        if not operation.done:
            return {"done": False}
        if operation.error.code:
            return {
                "done": True,
                "error": {
                    "code": "speech_failed",
                    "message": "Transcription failed.",
                    "retryable": operation.error.code in {4, 8, 13, 14},
                },
            }

        response = self.types.BatchRecognizeResponse.deserialize(operation.response.value)
        input_uri = f"gs://{self.bucket}/{job['objectName']}"
        file_result = response.results.get(input_uri)
        result_uri = batch_result_uri(file_result)
        if not file_result or not result_uri:
            return {
                "done": True,
                "error": {
                    "code": "speech_result_missing",
                    "message": "Transcription finished without a result.",
                    "retryable": True,
                },
            }

        from google.api_core.exceptions import NotFound

        try:
            payload = self.storage.read_text(result_uri)
        except NotFound:
            if self.storage.object_info(job["objectName"]) is None:
                raise
            return {
                "done": True,
                "error": {
                    "code": "speech_result_missing",
                    "message": "The transcription result is no longer available.",
                    "retryable": True,
                },
            }
        results = self.types.BatchRecognizeResults.from_json(
            payload, ignore_unknown_fields=True
        )
        transcript = " ".join(
            result.alternatives[0].transcript.strip()
            for result in results.results
            if result.alternatives and result.alternatives[0].transcript.strip()
        )
        return {"done": True, "transcript": transcript}

    def find_operation(self, job: dict) -> str | None:
        input_uri = f"gs://{self.bucket}/{job['objectName']}"
        output_uri = f"gs://{self.bucket}/results/{job['id']}/"
        started_at = job.get("submitStartedAt")
        started = (
            datetime.fromisoformat(started_at.replace("Z", "+00:00"))
            if started_at
            else None
        )
        operations = self.client.transport.operations_client.list_operations(
            f"projects/{self.project}/locations/{self.location}",
            "",
            timeout=20,
        )
        matching = []
        for operation in operations:
            try:
                metadata = self.types.OperationMetadata.deserialize(
                    operation.metadata.value
                )
            except Exception:
                continue
            request = metadata.batch_recognize_request
            if not request or len(request.files) != 1:
                continue
            created = metadata.create_time
            if started and not created:
                continue
            if started and created < started - timedelta(seconds=30):
                continue
            configured_output = request.recognition_output_config.gcs_output_config.uri
            if request.files[0].uri == input_uri and configured_output == output_uri:
                matching.append((created or datetime.min.replace(tzinfo=timezone.utc), operation.name))
        return max(matching, key=lambda match: match[0])[1] if matching else None

    def operation_done(self, job: dict) -> bool:
        from google.api_core.exceptions import NotFound

        try:
            operation = self.client.transport.operations_client.get_operation(
                job["operationName"], timeout=20
            )
        except NotFound:
            return True
        return operation.done

    def cancel(self, job: dict) -> None:
        self.client.transport.operations_client.cancel_operation(
            job["operationName"], timeout=20
        )


class VertexGemini:
    def __init__(self, project: str, location: str, default_model: str):
        from google import genai
        from google.genai import types

        self.client = genai.Client(
            vertexai=True,
            project=project,
            location=location,
            http_options=types.HttpOptions(timeout=GEMINI_TIMEOUT_MS),
        )
        self.default_model = default_model

    def generate(self, text: str, prompt: str, model: str | None = None) -> tuple[str, str]:
        from google.genai import types

        chosen_model = model or self.default_model
        response = self.client.models.generate_content(
            model=chosen_model,
            contents=text,
            config=types.GenerateContentConfig(
                system_instruction=prompt,
                max_output_tokens=GEMINI_MAX_OUTPUT_TOKENS,
            ),
        )
        generated = (response.text or "").strip()
        if not generated:
            raise RuntimeError("Gemini returned no text")
        return generated, chosen_model


def default_services(config: dict) -> dict:
    from google.cloud import firestore

    import firebase_admin
    from firebase_admin import auth

    if not firebase_admin._apps:
        firebase_admin.initialize_app(options={"projectId": config["project"]})

    storage = GoogleStorage(config["project"], config["bucket"])
    gemini = None
    if config["vertex_enabled"]:
        gemini = VertexGemini(
            config["project"], config["vertex_location"], config["gemini_model"]
        )

    def verify_token(token: str) -> dict:
        try:
            return auth.verify_id_token(token, check_revoked=True)
        except (
            ValueError,
            auth.InvalidIdTokenError,
            auth.UserDisabledError,
            auth.UserNotFoundError,
        ) as error:
            raise InvalidAuthToken from error

    return {
        "verify_token": verify_token,
        "jobs": FirestoreJobs(
            firestore.Client(project=config["project"]), config["firestore_collection"]
        ),
        "storage": storage,
        "speech": GoogleSpeech(
            config["project"],
            config["speech_location"],
            config["bucket"],
            storage,
        ),
        "gemini": gemini,
    }


def error_response(code: str, message: str, status: int):
    return jsonify({"error": {"code": code, "message": message}}), status


def public_job(job: dict) -> dict:
    polished = job.get("polishedTranscript")
    raw = job.get("rawTranscript")
    return {
        "id": job["id"],
        "state": PUBLIC_STATES.get(job["state"], job["state"]),
        "createdAt": job["createdAt"],
        "updatedAt": job["updatedAt"],
        "contentType": job["contentType"],
        "byteLength": job["byteLength"],
        "durationMs": job["durationMs"],
        "languageCode": job["languageCode"],
        "cleanupRequested": job["cleanupRequested"],
        "transcript": polished or raw,
        "rawTranscript": raw,
        "polishedTranscript": polished,
        "error": job.get("error"),
    }


def job_id_for(uid: str, idempotency_key: str) -> str:
    return hashlib.sha256(f"{uid}:{idempotency_key}".encode()).hexdigest()[:32]


def generation_id_for(uid: str, recording_id: str, idempotency_key: str) -> str:
    dictation_id = job_id_for(uid, recording_id)
    digest = hashlib.sha256(
        f"text:{uid}:{dictation_id}:{idempotency_key}".encode()
    ).hexdigest()[:32]
    return f"text-{digest}"


def generation_request_hash(text: str, prompt: str, model: str | None) -> str:
    payload = json.dumps(
        {"model": model, "prompt": prompt, "text": text},
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    return hashlib.sha256(payload.encode()).hexdigest()


def parse_create_request() -> tuple[dict | None, tuple | None]:
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return None, error_response("invalid_json", "A JSON body is required.", 400)

    content_type = body.get("contentType")
    if content_type not in CONTENT_TYPES:
        return None, error_response(
            "unsupported_audio", "Use audio/mp4, audio/wav, or audio/mpeg.", 400
        )

    byte_length = body.get("byteLength")
    duration_ms = body.get("durationMs")
    if (
        isinstance(byte_length, bool)
        or not isinstance(byte_length, int)
        or byte_length <= 0
        or byte_length > MAX_AUDIO_BYTES
    ):
        return None, error_response(
            "invalid_byte_length",
            "byteLength must be between 1 and 268435456.",
            400,
        )
    if (
        isinstance(duration_ms, bool)
        or not isinstance(duration_ms, int)
        or duration_ms <= 0
        or duration_ms > MAX_DURATION_MS
    ):
        return None, error_response(
            "invalid_duration", "durationMs must be between 1 and 3600000.", 400
        )

    crc32c = body.get("crc32c")
    try:
        checksum = base64.b64decode(crc32c, validate=True)
    except (TypeError, ValueError):
        checksum = b""
    if len(checksum) != 4:
        return None, error_response(
            "invalid_crc32c", "crc32c must be a base64-encoded CRC32C checksum.", 400
        )

    language_code = body.get("languageCode", "en-US")
    if not isinstance(language_code, str) or not LANGUAGE_CODE.fullmatch(language_code):
        return None, error_response(
            "invalid_language", "languageCode must be a BCP-47 language code.", 400
        )
    cleanup = body.get("cleanup", False)
    if not isinstance(cleanup, bool):
        return None, error_response("invalid_cleanup", "cleanup must be a boolean.", 400)
    if cleanup:
        return None, error_response(
            "invalid_cleanup",
            "Server-side dictation cleanup is unavailable. Use text generation later.",
            400,
        )

    return {
        "contentType": content_type,
        "byteLength": byte_length,
        "durationMs": duration_ms,
        "crc32c": crc32c,
        "languageCode": language_code,
        "cleanupRequested": False,
    }, None


def object_matches(job: dict, info: dict | None) -> bool:
    return bool(
        info
        and info["size"] == job["byteLength"]
        and info["crc32c"] == job["crc32c"]
        and info["contentType"] == job["contentType"]
    )


def upload_response(job: dict, session_url: str) -> dict:
    return {
        "sessionUrl": session_url,
        "method": "PUT",
        "expiresInSeconds": UPLOAD_SESSION_SECONDS,
        "chunkSizeBytes": UPLOAD_CHUNK_SIZE,
        "contentType": job["contentType"],
        "byteLength": job["byteLength"],
        "crc32c": job["crc32c"],
    }


def create_app(config: dict | None = None, services: dict | None = None) -> Flask:
    config = config or load_config()
    services = services or default_services(config)
    app = Flask(__name__)
    app.config["JSON_SORT_KEYS"] = False
    dictation_usage_limits = {
        "dictations": configured_limit(
            config, "daily_dictation_limit", DEFAULT_DAILY_DICTATION_LIMIT
        ),
        "audioBytes": configured_limit(
            config, "daily_audio_bytes_limit", DEFAULT_DAILY_AUDIO_BYTES_LIMIT
        ),
        "audioDurationMs": configured_limit(
            config,
            "daily_audio_duration_ms_limit",
            DEFAULT_DAILY_AUDIO_DURATION_MS_LIMIT,
        ),
    }
    speech_usage_limits = {
        "speechSubmissions": configured_limit(
            config,
            "daily_speech_submission_limit",
            DEFAULT_DAILY_SPEECH_SUBMISSION_LIMIT,
        )
    }
    vertex_usage_limits = {
        "vertexRequests": configured_limit(
            config, "daily_vertex_request_limit", DEFAULT_DAILY_VERTEX_REQUEST_LIMIT
        )
    }

    @app.after_request
    def disable_api_caching(response):
        if request.path.startswith("/v1/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    @app.before_request
    def authenticate():
        if request.path == "/healthz":
            return None
        if not request.path.startswith("/v1/"):
            return None
        header = request.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return error_response("unauthenticated", "Sign in is required.", 401)
        try:
            claims = services["verify_token"](header[7:])
        except InvalidAuthToken:
            return error_response("unauthenticated", "The sign-in token is invalid.", 401)
        except Exception as error:
            app.logger.warning(
                "Firebase token verification failed [%s]", type(error).__name__
            )
            return error_response(
                "authentication_unavailable",
                "Sign-in verification is unavailable. Try again.",
                503,
            )
        if claims.get("uid") != config["allowed_uid"]:
            return error_response("forbidden", "This account is not allowed.", 403)
        if claims.get("email_verified") is not True:
            return error_response("forbidden", "A verified Google account is required.", 403)
        g.uid = claims["uid"]
        return None

    def owned_job(job_id: str):
        if not DICTATION_ID.fullmatch(job_id):
            return None, error_response("not_found", "Dictation not found.", 404)
        job = services["jobs"].get(job_id)
        if not job or job.get("uid") != g.uid:
            return None, error_response("not_found", "Dictation not found.", 404)
        return job, None

    def ensure_upload_session(job: dict, force_new: bool = False):
        existing_url = job.get("uploadSessionUrl")
        existing_started = job.get("uploadSessionCreatedAt")
        if (
            existing_url
            and not force_new
            and not is_stale(existing_started, UPLOAD_SESSION_REUSE_SECONDS)
        ):
            return job, existing_url, None
        if existing_url:
            try:
                services["storage"].cancel_upload_session(existing_url)
            except Exception as error:
                app.logger.warning(
                    "Upload session cancellation failed for %s [%s]",
                    job["id"],
                    type(error).__name__,
                )
                return job, None, error_response(
                    "upload_session_failed",
                    "The old upload could not be stopped. Try again.",
                    503,
                )
        try:
            session_url = services["storage"].create_upload_session(job)
        except Exception as error:
            app.logger.warning(
                "Upload session creation failed for %s [%s]",
                job["id"],
                type(error).__name__,
            )
            return job, None, error_response(
                "upload_session_failed", "The upload could not start. Try again.", 503
            )
        started_at = utc_now()
        current, won = services["jobs"].claim(
            job["id"],
            {"AWAITING_UPLOAD"},
            {
                "uploadSessionUrl": session_url,
                "uploadSessionCreatedAt": started_at,
                "updatedAt": started_at,
            },
            {
                "uploadSessionUrl": existing_url,
                "uploadSessionCreatedAt": existing_started,
            },
        )
        if won and current:
            return current, session_url, None
        try:
            services["storage"].cancel_upload_session(session_url)
        except Exception as error:
            app.logger.warning(
                "Unused upload session cancellation failed for %s [%s]",
                job["id"],
                type(error).__name__,
            )
        if not current:
            return None, None, error_response("not_found", "Dictation not found.", 404)
        if current["state"] == "DELETING":
            return current, None, error_response(
                "deletion_in_progress", "This dictation is being deleted.", 409
            )
        if current["state"] == "AWAITING_UPLOAD" and current.get("uploadSessionUrl"):
            return current, current["uploadSessionUrl"], None
        return current, None, None

    def record_submitted_operation(job: dict, operation_name: str) -> dict:
        expected = {
            "submitLease": job.get("submitLease"),
            "submitStartedAt": job.get("submitStartedAt"),
        }
        current, won = services["jobs"].claim(
            job["id"],
            {"SUBMITTING"},
            {
                "state": "TRANSCRIBING",
                "operationName": operation_name,
                "submitLease": None,
                "submitStartedAt": None,
                "updatedAt": utc_now(),
            },
            expected,
        )
        if won or not current:
            return current or job
        if current["state"] == "DELETING":
            current, _ = services["jobs"].claim(
                job["id"],
                {"DELETING"},
                {
                    "operationName": operation_name,
                    "submitLease": None,
                    "submitStartedAt": None,
                    "updatedAt": utc_now(),
                },
                expected,
            )
        return current or job

    def start_transcription(job: dict) -> dict:
        if job["state"] in {"SUBMITTING", "TRANSCRIBING", "READY", "DELETING"}:
            return job
        timestamp = utc_now()
        lease = secrets.token_hex(16)
        claimed, won = services["jobs"].claim(
            job["id"],
            {"AWAITING_UPLOAD"},
            {
                "state": "SUBMITTING",
                "submitLease": lease,
                "submitStartedAt": timestamp,
                "uploadSessionUrl": None,
                "uploadSessionCreatedAt": None,
                "updatedAt": timestamp,
                "error": None,
            },
        )
        if not won or not claimed:
            return claimed or job
        expected = {
            "submitLease": claimed.get("submitLease"),
            "submitStartedAt": claimed.get("submitStartedAt"),
        }
        try:
            allowed = services["jobs"].reserve_usage(
                claimed["uid"],
                {"speechSubmissions": 1},
                speech_usage_limits,
            )
        except Exception as error:
            app.logger.warning(
                "Speech usage reservation failed for %s [%s]",
                job["id"],
                type(error).__name__,
            )
            return services["jobs"].get(job["id"]) or claimed
        if not allowed:
            failed, _ = services["jobs"].claim(
                job["id"],
                {"SUBMITTING"},
                {
                    "state": "FAILED",
                    "submitLease": None,
                    "submitStartedAt": None,
                    "updatedAt": utc_now(),
                    "error": {
                        "code": "daily_speech_limit",
                        "message": "The daily cloud transcription limit was reached.",
                        "retryable": True,
                    },
                },
                expected,
            )
            return failed or claimed
        try:
            operation_name = services["speech"].submit(claimed)
        except Exception as error:
            app.logger.warning(
                "Speech submission failed for %s [%s]",
                job["id"],
                type(error).__name__,
            )
            failure = deterministic_provider_failure(error)
            if failure:
                failure.update(
                    {
                        "code": "speech_submission_rejected",
                        "message": "Speech rejected the transcription request.",
                    }
                )
                failed, _ = services["jobs"].claim(
                    job["id"],
                    {"SUBMITTING"},
                    {
                        "state": "FAILED",
                        "submitLease": None,
                        "submitStartedAt": None,
                        "updatedAt": utc_now(),
                        "error": failure,
                    },
                    expected,
                )
                return failed or claimed
            # The request may have reached Speech even when its response timed out. Keep the
            # lease and its exact input/output fingerprint intact. A later poll searches Speech
            # operations before it ever clears the lease or submits again.
            return services["jobs"].get(job["id"]) or claimed
        return record_submitted_operation(claimed, operation_name)

    def recover_stale_submit(job: dict) -> dict:
        if job["state"] != "SUBMITTING" or not is_stale(job.get("submitStartedAt")):
            return job
        expected = {
            "submitLease": job.get("submitLease"),
            "submitStartedAt": job.get("submitStartedAt"),
        }
        try:
            operation_name = services["speech"].find_operation(job)
        except Exception as error:
            app.logger.warning(
                "Speech operation recovery failed for %s [%s]",
                job["id"],
                type(error).__name__,
            )
            return job
        if operation_name:
            return record_submitted_operation(job, operation_name)
        current, won = services["jobs"].claim(
            job["id"],
            {"SUBMITTING"},
            {
                "state": "AWAITING_UPLOAD",
                "operationName": None,
                "submitLease": None,
                "submitStartedAt": None,
                "updatedAt": utc_now(),
            },
            expected,
        )
        if not won or not current:
            return current or job
        return start_transcription(current)

    def continue_deletion(job: dict):
        needs_final_sweep = bool(
            job.get("operationName")
            or job.get("submitLease")
            or job.get("uploadSessionUrl")
        )
        if job.get("uploadSessionUrl"):
            session_url = job["uploadSessionUrl"]
            try:
                services["storage"].cancel_upload_session(session_url)
            except Exception as error:
                app.logger.warning(
                    "Upload session deletion failed for %s [%s]",
                    job["id"],
                    type(error).__name__,
                )
                return job, error_response(
                    "delete_pending",
                    "Cloud deletion is still stopping the upload. Try again.",
                    503,
                )
            job, _ = services["jobs"].claim(
                job["id"],
                {"DELETING"},
                {
                    "uploadSessionUrl": None,
                    "uploadSessionCreatedAt": None,
                    "updatedAt": utc_now(),
                },
                {"uploadSessionUrl": session_url},
            )
            if not job:
                return None, None

        generation_ids = [
            generation_id
            for generation_id in (job.get("generationIds") or [])
            if isinstance(generation_id, str) and generation_id.startswith("text-")
        ]
        try:
            services["jobs"].delete_many(generation_ids)
        except Exception as error:
            app.logger.warning(
                "Generation record deletion failed for %s [%s]",
                job["id"],
                type(error).__name__,
            )
            return job, error_response(
                "delete_failed", "Generated text could not be deleted. Try again.", 503
            )
        try:
            services["storage"].delete_job_objects(job)
        except Exception as error:
            app.logger.warning(
                "Cloud object deletion failed for %s [%s]",
                job["id"],
                type(error).__name__,
            )
            return job, error_response(
                "delete_failed", "Cloud audio could not be deleted. Try again.", 503
            )

        if not job.get("operationName") and job.get("submitLease"):
            if not is_stale(job.get("submitStartedAt")):
                return job, None
            try:
                operation_name = services["speech"].find_operation(job)
            except Exception as error:
                app.logger.warning(
                    "Deleting Speech operation recovery failed for %s [%s]",
                    job["id"],
                    type(error).__name__,
                )
                return job, error_response(
                    "delete_pending",
                    "Cloud deletion is still checking Speech. Try again.",
                    503,
                )
            if operation_name:
                job = record_submitted_operation(job, operation_name)
            else:
                job, _ = services["jobs"].claim(
                    job["id"],
                    {"DELETING"},
                    {
                        "submitLease": None,
                        "submitStartedAt": None,
                        "updatedAt": utc_now(),
                    },
                    {
                        "submitLease": job.get("submitLease"),
                        "submitStartedAt": job.get("submitStartedAt"),
                    },
                )
                if not job:
                    return None, None

        if job.get("operationName"):
            try:
                if not services["speech"].operation_done(job):
                    if not job.get("cancelRequestedAt"):
                        services["speech"].cancel(job)
                        job = services["jobs"].patch(
                            job["id"],
                            {
                                "cancelRequestedAt": utc_now(),
                                "updatedAt": utc_now(),
                            },
                        )
                    return job, None
            except Exception as error:
                app.logger.warning(
                    "Speech cancellation failed for %s [%s]",
                    job["id"],
                    type(error).__name__,
                )
                return job, error_response(
                    "delete_pending",
                    "Cloud deletion is still stopping Speech. Try again.",
                    503,
                )

        if needs_final_sweep:
            try:
                services["storage"].delete_job_objects(job)
            except Exception as error:
                app.logger.warning(
                    "Final cloud object deletion failed for %s [%s]",
                    job["id"],
                    type(error).__name__,
                )
                return job, error_response(
                    "delete_failed", "Cloud audio could not be deleted. Try again.", 503
                )
        try:
            services["jobs"].delete(job["id"])
        except Exception as error:
            app.logger.warning(
                "Firestore job deletion failed for %s [%s]",
                job["id"],
                type(error).__name__,
            )
            return job, error_response(
                "delete_failed", "Cloud job state could not be deleted. Try again.", 503
            )
        return None, None

    @app.get("/healthz")
    def health():
        return jsonify({"ok": True})

    @app.post("/v1/dictations")
    def create_dictation():
        idempotency_key = request.headers.get("Idempotency-Key", "")
        if not 8 <= len(idempotency_key) <= 128:
            return error_response(
                "invalid_idempotency_key",
                "Idempotency-Key must be a stable recording identifier.",
                400,
            )
        fields, validation_error = parse_create_request()
        if validation_error:
            return validation_error

        job_id = job_id_for(g.uid, idempotency_key)
        created_at = datetime.now(timezone.utc)
        timestamp = utc_now(created_at)
        extension = CONTENT_TYPES[fields["contentType"]]
        candidate = {
            "id": job_id,
            "uid": g.uid,
            "state": "AWAITING_UPLOAD",
            "createdAt": timestamp,
            "updatedAt": timestamp,
            "expiresAt": retention_deadline(created_at),
            "objectName": f"audio/{job_id}.{extension}",
            "uploadSessionUrl": None,
            "uploadSessionCreatedAt": None,
            "submitLease": None,
            "submitStartedAt": None,
            "operationName": None,
            "generationIds": [],
            "rawTranscript": None,
            "polishedTranscript": None,
            "error": None,
            **fields,
        }
        try:
            job, created, create_error = services["jobs"].create_dictation(
                candidate,
                {
                    "dictations": 1,
                    "audioBytes": fields["byteLength"],
                    "audioDurationMs": fields["durationMs"],
                },
                dictation_usage_limits,
            )
        except Exception as error:
            app.logger.warning(
                "Dictation usage reservation failed [%s]", type(error).__name__
            )
            return error_response(
                "dictation_state_unavailable",
                "The dictation could not be saved. Try again.",
                503,
            )
        if create_error == "daily_limit" or not job:
            return error_response(
                "daily_dictation_limit",
                "The daily cloud dictation limit was reached.",
                429,
            )
        immutable = (
            "contentType",
            "byteLength",
            "durationMs",
            "crc32c",
            "languageCode",
            "cleanupRequested",
        )
        if any(job.get(field) != candidate[field] for field in immutable):
            return error_response(
                "idempotency_conflict",
                "This Idempotency-Key belongs to different audio.",
                409,
            )
        if not isinstance(job.get("expiresAt"), datetime):
            job = services["jobs"].patch(
                job["id"], {"expiresAt": candidate["expiresAt"]}
            )

        upload = None
        if job["state"] == "AWAITING_UPLOAD":
            try:
                info = services["storage"].object_info(job["objectName"])
            except Exception:
                return error_response(
                    "storage_unavailable", "Cloud storage is unavailable. Try again.", 503
                )
            if not object_matches(job, info):
                job, session_url, session_error = ensure_upload_session(job)
                if session_error:
                    return session_error
                if session_url:
                    upload = upload_response(job, session_url)
        return jsonify({"job": public_job(job), "upload": upload}), 201 if created else 200

    @app.post("/v1/dictations/<job_id>/commit")
    def commit_dictation(job_id: str):
        job, missing = owned_job(job_id)
        if missing:
            return missing
        if job["state"] == "DELETING":
            return error_response(
                "deletion_in_progress", "This dictation is being deleted.", 409
            )
        job = recover_stale_submit(job)
        if job["state"] in {"SUBMITTING", "TRANSCRIBING", "READY"}:
            status = 200 if job["state"] == "READY" else 202
            return jsonify({"job": public_job(job)}), status
        if job["state"] == "FAILED":
            return error_response(
                "retry_required", "Use the retry endpoint for this dictation.", 409
            )

        try:
            info = services["storage"].object_info(job["objectName"])
        except Exception:
            return error_response(
                "storage_unavailable", "Cloud storage is unavailable. Try again.", 503
            )
        if info is None:
            return error_response(
                "upload_incomplete", "The audio upload has not finished.", 409
            )
        if not object_matches(job, info):
            job, won = services["jobs"].claim(
                job_id,
                {"AWAITING_UPLOAD"},
                {
                    "state": "FAILED",
                    "updatedAt": utc_now(),
                    "error": {
                        "code": "upload_invalid",
                        "message": "The uploaded audio failed its integrity check.",
                        "retryable": True,
                    },
                },
            )
            if not job:
                return error_response("not_found", "Dictation not found.", 404)
            if not won:
                if job["state"] == "DELETING":
                    return error_response(
                        "deletion_in_progress", "This dictation is being deleted.", 409
                    )
                status = 200 if job["state"] in {"READY", "FAILED"} else 202
                return jsonify({"job": public_job(job)}), status
            return jsonify({"job": public_job(job)}), 422

        job = start_transcription(job)
        return jsonify({"job": public_job(job)}), 202

    @app.get("/v1/dictations/<job_id>")
    def get_dictation(job_id: str):
        job, missing = owned_job(job_id)
        if missing:
            return missing
        if job["state"] == "DELETING":
            job, deletion_error = continue_deletion(job)
            if deletion_error:
                return deletion_error
            if job is None:
                return error_response("not_found", "Dictation not found.", 404)
            return jsonify({"job": public_job(job)})
        job = recover_stale_submit(job)
        if job["state"] == "TRANSCRIBING":
            try:
                result = services["speech"].poll(job)
            except Exception:
                return error_response(
                    "speech_poll_failed", "Transcription status is unavailable.", 503
                )
            if result.get("done") and result.get("error"):
                job, _ = services["jobs"].claim(
                    job_id,
                    {"TRANSCRIBING"},
                    {
                        "state": "FAILED",
                        "updatedAt": utc_now(),
                        "error": result["error"],
                    },
                )
            elif result.get("done"):
                transcript = result.get("transcript", "")
                job, _ = services["jobs"].claim(
                    job_id,
                    {"TRANSCRIBING"},
                    {
                        "state": "READY",
                        "rawTranscript": transcript,
                        "updatedAt": utc_now(),
                        "error": None,
                    },
                )
            if job is None:
                return error_response("not_found", "Dictation not found.", 404)
        response = jsonify({"job": public_job(job)})
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.post("/v1/dictations/<job_id>/retry")
    def retry_dictation(job_id: str):
        job, missing = owned_job(job_id)
        if missing:
            return missing
        if job["state"] == "DELETING":
            return error_response(
                "deletion_in_progress", "This dictation is being deleted.", 409
            )
        if job["state"] != "FAILED":
            return error_response(
                "not_retryable", "Only a failed dictation can be retried.", 409
            )
        if job.get("error") and job["error"].get("retryable") is not True:
            return error_response(
                "not_retryable", "This dictation failure cannot be retried.", 409
            )
        job, won = services["jobs"].claim(
            job_id,
            {"FAILED"},
            {
                "state": "AWAITING_UPLOAD",
                "operationName": None,
                "submitLease": None,
                "submitStartedAt": None,
                "error": None,
                "updatedAt": utc_now(),
            },
        )
        if not job:
            return error_response("not_found", "Dictation not found.", 404)
        if not won:
            if job["state"] == "DELETING":
                return error_response(
                    "deletion_in_progress", "This dictation is being deleted.", 409
                )
            return error_response(
                "not_retryable", "Only a failed dictation can be retried.", 409
            )
        try:
            info = services["storage"].object_info(job["objectName"])
        except Exception:
            return error_response(
                "storage_unavailable", "Cloud storage is unavailable. Try again.", 503
            )
        if object_matches(job, info):
            job = start_transcription(job)
            return jsonify({"job": public_job(job), "upload": None}), 202
        if info is not None:
            services["storage"].delete_job_objects(job)
        job, session_url, session_error = ensure_upload_session(job, force_new=True)
        if session_error:
            return session_error
        return jsonify(
            {
                "job": public_job(job),
                "upload": upload_response(job, session_url) if session_url else None,
            }
        )

    @app.delete("/v1/dictations/<job_id>")
    def delete_dictation(job_id: str):
        job, missing = owned_job(job_id)
        if missing:
            return missing
        if job["state"] != "DELETING":
            job, _ = services["jobs"].claim(
                job_id,
                {"AWAITING_UPLOAD", "SUBMITTING", "TRANSCRIBING", "READY", "FAILED"},
                {
                    "state": "DELETING",
                    "deletionRequestedAt": utc_now(),
                    "updatedAt": utc_now(),
                    "error": None,
                },
            )
            if not job:
                return error_response("not_found", "Dictation not found.", 404)
        job, deletion_error = continue_deletion(job)
        if deletion_error:
            return deletion_error
        if job is not None:
            return jsonify({"job": public_job(job)}), 202
        return "", 204

    @app.post("/v1/text:generate")
    def generate_text():
        idempotency_key = request.headers.get("Idempotency-Key", "")
        if not 8 <= len(idempotency_key) <= 128:
            return error_response(
                "invalid_idempotency_key",
                "Idempotency-Key must be a stable generation identifier.",
                400,
            )
        recording_id = request.headers.get("Recording-Id", "")
        if not 8 <= len(recording_id) <= 128:
            return error_response(
                "invalid_recording_id",
                "Recording-Id must be the stable local recording identifier.",
                400,
            )
        body = request.get_json(silent=True)
        if not isinstance(body, dict):
            return error_response("invalid_json", "A JSON body is required.", 400)
        text = body.get("text")
        prompt = body.get("prompt")
        model = body.get("model")
        if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT_CHARS:
            return error_response(
                "invalid_text", "text must be between 1 and 250000 characters.", 400
            )
        if (
            not isinstance(prompt, str)
            or not prompt.strip()
            or len(prompt) > MAX_PROMPT_CHARS
        ):
            return error_response(
                "invalid_prompt", "prompt must be between 1 and 30000 characters.", 400
            )
        if model is not None and (
            not isinstance(model, str)
            or not MODEL_NAME.fullmatch(model)
            or model != config["gemini_model"]
        ):
            return error_response("invalid_model", "model is not valid.", 400)
        if services.get("gemini") is None:
            return error_response("vertex_disabled", "Vertex Gemini is disabled.", 503)

        chosen_model = model or config["gemini_model"]
        request_hash = generation_request_hash(text, prompt, model)
        dictation_id = job_id_for(g.uid, recording_id)
        generation_id = generation_id_for(g.uid, recording_id, idempotency_key)
        created_at = datetime.now(timezone.utc)
        timestamp = utc_now(created_at)
        generation_lease = secrets.token_hex(16)
        candidate = {
            "id": generation_id,
            "uid": g.uid,
            "kind": "TEXT_GENERATION",
            "dictationId": dictation_id,
            "state": "GENERATING",
            "requestHash": request_hash,
            "generationLease": generation_lease,
            "generationStartedAt": timestamp,
            "createdAt": timestamp,
            "updatedAt": timestamp,
            "expiresAt": retention_deadline(created_at),
            "model": chosen_model,
            "generatedText": None,
            "error": None,
        }
        try:
            generation, created, link_error = services["jobs"].create_generation(
                candidate, dictation_id
            )
        except Exception:
            return error_response(
                "generation_state_unavailable",
                "Generation state could not be saved. Try again.",
                503,
            )
        if link_error == "not_found":
            return error_response("not_found", "Dictation not found.", 404)
        if link_error == "deleting":
            return error_response(
                "deletion_in_progress", "This dictation is being deleted.", 409
            )
        if link_error == "not_ready":
            return error_response(
                "dictation_not_ready",
                "The dictation must finish transcription before text generation.",
                409,
            )
        if link_error == "generation_limit":
            return error_response(
                "recording_generation_limit",
                "This recording has reached its cloud generation limit.",
                429,
            )
        if not generation:
            return error_response(
                "generation_state_unavailable",
                "Generation state could not be saved. Try again.",
                503,
            )

        if (
            generation.get("uid") != g.uid
            or generation.get("kind") != "TEXT_GENERATION"
            or generation.get("dictationId") != dictation_id
            or generation.get("requestHash") != request_hash
        ):
            return error_response(
                "idempotency_conflict",
                "This Idempotency-Key belongs to a different generation request.",
                409,
            )

        if generation.get("state") == "READY":
            generated = generation.get("generatedText")
            stored_model = generation.get("model")
            if not isinstance(generated, str) or not isinstance(stored_model, str):
                return error_response(
                    "generation_state_unavailable",
                    "The saved generation response is unavailable.",
                    503,
                )
            return jsonify({"text": generated, "model": stored_model})

        if not created:
            state = generation.get("state")
            if state == "GENERATING" and not is_stale(
                generation.get("generationStartedAt"), STALE_GENERATION_SECONDS
            ):
                return jsonify({"state": "GENERATING"}), 202
            if state not in {"GENERATING", "FAILED"}:
                return error_response(
                    "generation_state_unavailable",
                    "The saved generation state is unavailable.",
                    503,
                )
            if state == "FAILED" and (generation.get("error") or {}).get(
                "retryable"
            ) is False:
                return error_response(
                    "generation_failed",
                    "Vertex Gemini rejected this generation request.",
                    422,
                )
            chosen_model = generation.get("model")
            if not isinstance(chosen_model, str):
                return error_response(
                    "generation_state_unavailable",
                    "The saved generation state is unavailable.",
                    503,
                )
            generation_lease = secrets.token_hex(16)
            generation_started_at = utc_now()
            expected = {
                "uid": g.uid,
                "kind": "TEXT_GENERATION",
                "dictationId": dictation_id,
                "requestHash": request_hash,
            }
            if state == "GENERATING":
                expected.update(
                    {
                        "generationLease": generation.get("generationLease"),
                        "generationStartedAt": generation.get(
                            "generationStartedAt"
                        ),
                    }
                )
            generation, won = services["jobs"].claim(
                generation_id,
                {state},
                {
                    "state": "GENERATING",
                    "generationLease": generation_lease,
                    "generationStartedAt": generation_started_at,
                    "updatedAt": generation_started_at,
                    "error": None,
                },
                expected,
            )
            if not won or not generation:
                if generation and generation.get("state") == "READY":
                    generated = generation.get("generatedText")
                    stored_model = generation.get("model")
                    if isinstance(generated, str) and isinstance(stored_model, str):
                        return jsonify({"text": generated, "model": stored_model})
                return jsonify({"state": "GENERATING"}), 202

        try:
            allowed = services["jobs"].reserve_usage(
                g.uid, {"vertexRequests": 1}, vertex_usage_limits
            )
        except Exception as error:
            app.logger.warning(
                "Vertex usage reservation failed for %s [%s]",
                generation_id,
                type(error).__name__,
            )
            try:
                services["jobs"].claim(
                    generation_id,
                    {"GENERATING"},
                    {
                        "state": "FAILED",
                        "generationLease": None,
                        "generationStartedAt": None,
                        "updatedAt": utc_now(),
                        "error": {
                            "code": "generation_state_unavailable",
                            "message": "Generation state is unavailable.",
                            "retryable": True,
                        },
                    },
                    {
                        "requestHash": request_hash,
                        "generationLease": generation_lease,
                    },
                )
            except Exception:
                pass
            return error_response(
                "generation_state_unavailable",
                "Generation state could not be saved. Try again.",
                503,
            )
        if not allowed:
            try:
                services["jobs"].claim(
                    generation_id,
                    {"GENERATING"},
                    {
                        "state": "FAILED",
                        "generationLease": None,
                        "generationStartedAt": None,
                        "updatedAt": utc_now(),
                        "error": {
                            "code": "daily_vertex_limit",
                            "message": "The daily cloud text limit was reached.",
                            "retryable": True,
                        },
                    },
                    {
                        "requestHash": request_hash,
                        "generationLease": generation_lease,
                    },
                )
            except Exception:
                pass
            return error_response(
                "daily_vertex_limit",
                "The daily cloud text limit was reached.",
                429,
            )

        try:
            generated, chosen_model = services["gemini"].generate(
                text, prompt, chosen_model
            )
        except Exception as error:
            app.logger.warning(
                "Vertex generation failed for %s [%s]",
                generation_id,
                type(error).__name__,
            )
            failure = deterministic_provider_failure(error)
            values = {
                "updatedAt": utc_now(),
                "error": {
                    "code": "generation_uncertain",
                    "message": "Vertex Gemini may still have processed this request.",
                    "retryable": True,
                },
            }
            if failure:
                failure.update(
                    {
                        "code": "generation_rejected",
                        "message": "Vertex Gemini rejected the generation request.",
                    }
                )
                values.update(
                    {
                        "state": "FAILED",
                        "generationLease": None,
                        "generationStartedAt": None,
                        "error": failure,
                    }
                )
            try:
                services["jobs"].claim(
                    generation_id,
                    {"GENERATING"},
                    values,
                    {
                        "requestHash": request_hash,
                        "generationLease": generation_lease,
                    },
                )
            except Exception:
                app.logger.warning(
                    "Generation failure state could not be saved for %s", generation_id
                )
            status = 502
            if failure:
                status = 429 if provider_http_status(error) == 429 else 422
            return error_response(
                "generation_failed",
                "Vertex Gemini could not generate text.",
                status,
            )
        try:
            completed, won = services["jobs"].claim(
                generation_id,
                {"GENERATING"},
                {
                    "state": "READY",
                    "generatedText": generated,
                    "model": chosen_model,
                    "generationLease": None,
                    "generationStartedAt": None,
                    "updatedAt": utc_now(),
                    "error": None,
                },
                {
                    "requestHash": request_hash,
                    "generationLease": generation_lease,
                },
            )
        except Exception:
            return error_response(
                "generation_state_unavailable",
                "Generated text could not be saved. Try again.",
                503,
            )
        if not won or not completed:
            return error_response(
                "generation_state_unavailable",
                "Generated text could not be saved. Try again.",
                503,
            )
        return jsonify({"text": completed["generatedText"], "model": completed["model"]})

    return app
