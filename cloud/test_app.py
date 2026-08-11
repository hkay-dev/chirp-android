import copy
import threading
import unittest
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import patch

from app import (
    GEMINI_MAX_OUTPUT_TOKENS,
    GEMINI_TIMEOUT_MS,
    GoogleSpeech,
    GoogleStorage,
    InvalidAuthToken,
    VertexGemini,
    batch_result_uri,
    create_app,
    generation_id_for,
    retention_deadline,
)


CRC32C = "AAAAAA=="
AUTH = {"Authorization": "Bearer good"}


class FakeJobs:
    def __init__(self):
        self.data = {}
        self.usage = {}
        self.lock = threading.RLock()
        self.get_barrier = None
        self.delete_error = False

    def create(self, job):
        with self.lock:
            if job["id"] in self.data:
                return copy.deepcopy(self.data[job["id"]]), False
            self.data[job["id"]] = copy.deepcopy(job)
            return copy.deepcopy(job), True

    def create_dictation(self, job, increments, limits):
        with self.lock:
            if job["id"] in self.data:
                return copy.deepcopy(self.data[job["id"]]), False, None
            if any(
                self.usage.get(name, 0) + amount > limits[name]
                for name, amount in increments.items()
            ):
                return None, False, "daily_limit"
            for name, amount in increments.items():
                self.usage[name] = self.usage.get(name, 0) + amount
            self.data[job["id"]] = copy.deepcopy(job)
            return copy.deepcopy(job), True, None

    def create_generation(self, generation, dictation_id):
        with self.lock:
            dictation = self.data.get(dictation_id)
            if not dictation or dictation.get("uid") != generation["uid"]:
                return None, False, "not_found"
            if dictation.get("state") == "DELETING":
                return None, False, "deleting"
            if dictation.get("state") != "READY":
                return None, False, "not_ready"
            if generation["id"] in self.data:
                return copy.deepcopy(self.data[generation["id"]]), False, None
            generation_ids = dictation.setdefault("generationIds", [])
            if len(generation_ids) >= 20:
                return None, False, "generation_limit"
            generation_ids.append(generation["id"])
            self.data[generation["id"]] = copy.deepcopy(generation)
            return copy.deepcopy(generation), True, None

    def reserve_usage(self, uid, increments, limits):
        with self.lock:
            if any(
                self.usage.get(name, 0) + amount > limits[name]
                for name, amount in increments.items()
            ):
                return False
            for name, amount in increments.items():
                self.usage[name] = self.usage.get(name, 0) + amount
            return True

    def get(self, job_id):
        with self.lock:
            job = self.data.get(job_id)
            value = copy.deepcopy(job) if job else None
        if self.get_barrier:
            self.get_barrier.wait(timeout=3)
        return value

    def patch(self, job_id, values):
        with self.lock:
            self.data[job_id].update(copy.deepcopy(values))
            return copy.deepcopy(self.data[job_id])

    def claim(self, job_id, states, values, expected=None):
        with self.lock:
            job = self.data.get(job_id)
            if not job:
                return None, False
            matches = all(
                job.get(key) == value for key, value in (expected or {}).items()
            )
            won = job["state"] in states and matches
            if won:
                job.update(copy.deepcopy(values))
            return copy.deepcopy(job), won

    def delete(self, job_id):
        with self.lock:
            if self.delete_error:
                raise RuntimeError("firestore unavailable")
            del self.data[job_id]

    def delete_many(self, job_ids):
        with self.lock:
            if job_ids and self.delete_error:
                raise RuntimeError("firestore unavailable")
            for job_id in job_ids:
                self.data.pop(job_id, None)


class FakeStorage:
    def __init__(self):
        self.objects = {}
        self.sessions = []
        self.cancelled_sessions = []
        self.deleted = []
        self.delete_error = False

    def create_upload_session(self, job):
        url = f"https://upload.test/{job['id']}/{len(self.sessions)}"
        self.sessions.append(url)
        return url

    def cancel_upload_session(self, session_url):
        self.cancelled_sessions.append(session_url)

    def object_info(self, object_name):
        return copy.deepcopy(self.objects.get(object_name))

    def finish(self, job):
        self.objects[job["objectName"]] = {
            "size": job["byteLength"],
            "crc32c": job["crc32c"],
            "contentType": job["contentType"],
        }

    def delete_job_objects(self, job):
        if self.delete_error:
            raise RuntimeError("storage unavailable")
        self.objects.pop(job["objectName"], None)
        self.deleted.append(job["id"])


class FakeSpeech:
    def __init__(self):
        self.submissions = []
        self.result = {"done": False}
        self.recovered_operation = None
        self.operation_is_done = False
        self.cancelled = []
        self.submit_started = None
        self.release_submit = None
        self.submit_error = None

    def submit(self, job):
        self.submissions.append(job["id"])
        if self.submit_started:
            self.submit_started.set()
        if self.release_submit:
            self.release_submit.wait(timeout=3)
        if self.submit_error:
            raise self.submit_error
        return f"operations/{job['id']}"

    def poll(self, job):
        return copy.deepcopy(self.result)

    def find_operation(self, job):
        return self.recovered_operation

    def operation_done(self, job):
        return self.operation_is_done

    def cancel(self, job):
        self.cancelled.append(job["operationName"])


class FakeGemini:
    def __init__(self):
        self.calls = []
        self.generate_error = None
        self.generate_started = None
        self.release_generate = None

    def generate(self, text, prompt, model=None):
        chosen = model or "gemini-default"
        self.calls.append((text, prompt, chosen))
        if self.generate_started:
            self.generate_started.set()
        if self.release_generate:
            self.release_generate.wait(timeout=3)
        if self.generate_error:
            raise self.generate_error
        return f"generated {text}", chosen


class CloudApiTest(unittest.TestCase):
    def setUp(self):
        self.jobs = FakeJobs()
        self.storage = FakeStorage()
        self.speech = FakeSpeech()
        self.gemini = FakeGemini()

        def verify(token):
            if token == "bad":
                raise InvalidAuthToken("bad token")
            if token == "unavailable":
                raise RuntimeError("firebase unavailable")
            return {
                "uid": "other-user" if token == "other" else "only-user",
                "email_verified": token != "unverified",
            }

        self.config = {
            "project": "test-project",
            "bucket": "test-bucket",
            "allowed_uid": "only-user",
            "speech_location": "us",
            "firestore_collection": "dictations",
            "vertex_enabled": True,
            "vertex_location": "global",
            "gemini_model": "gemini-default",
        }
        services = {
            "verify_token": verify,
            "jobs": self.jobs,
            "storage": self.storage,
            "speech": self.speech,
            "gemini": self.gemini,
        }
        self.app = create_app(self.config, services)
        self.app.testing = True
        self.client = self.app.test_client()

    def body(self, **changes):
        body = {
            "contentType": "audio/mp4",
            "byteLength": 1024,
            "durationMs": 12_000,
            "crc32c": CRC32C,
            "languageCode": "en-US",
            "cleanup": False,
        }
        body.update(changes)
        return body

    def create(self, key="recording-123", **changes):
        return self.client.post(
            "/v1/dictations",
            json=self.body(**changes),
            headers={**AUTH, "Idempotency-Key": key},
        )

    def created_job(self):
        response = self.create()
        self.assertEqual(response.status_code, 201)
        job_id = response.get_json()["job"]["id"]
        return self.jobs.get(job_id)

    def ready_job(self, key="recording-123"):
        response = self.create(key=key)
        self.assertIn(response.status_code, {200, 201})
        job_id = response.get_json()["job"]["id"]
        return self.jobs.patch(
            job_id,
            {
                "state": "READY",
                "rawTranscript": "raw transcript",
            },
        )

    def generation_headers(self, key="generation-123", recording_id="recording-123"):
        return {
            **AUTH,
            "Idempotency-Key": key,
            "Recording-Id": recording_id,
        }

    def test_auth_is_required_and_uid_is_allowlisted(self):
        missing = self.client.post(
            "/v1/dictations",
            json=self.body(),
            headers={"Idempotency-Key": "recording-123"},
        )
        self.assertEqual(missing.status_code, 401)

        forbidden = self.client.post(
            "/v1/dictations",
            json=self.body(),
            headers={
                "Authorization": "Bearer other",
                "Idempotency-Key": "recording-123",
            },
        )
        self.assertEqual(forbidden.status_code, 403)

        unverified = self.client.post(
            "/v1/dictations",
            json=self.body(),
            headers={
                "Authorization": "Bearer unverified",
                "Idempotency-Key": "recording-123",
            },
        )
        self.assertEqual(unverified.status_code, 403)

        invalid = self.client.post(
            "/v1/dictations",
            json=self.body(),
            headers={
                "Authorization": "Bearer bad",
                "Idempotency-Key": "recording-123",
            },
        )
        self.assertEqual(invalid.status_code, 401)

        unavailable = self.client.post(
            "/v1/dictations",
            json=self.body(),
            headers={
                "Authorization": "Bearer unavailable",
                "Idempotency-Key": "recording-123",
            },
        )
        self.assertEqual(unavailable.status_code, 503)
        self.assertEqual(
            unavailable.get_json()["error"]["code"], "authentication_unavailable"
        )

    def test_create_returns_a_resumable_upload_and_is_idempotent(self):
        first = self.create()
        self.assertEqual(first.status_code, 201)
        payload = first.get_json()
        self.assertEqual(payload["job"]["state"], "AWAITING_UPLOAD")
        self.assertEqual(payload["upload"]["chunkSizeBytes"], 8 * 1024 * 1024)
        self.assertEqual(payload["upload"]["sessionUrl"], self.storage.sessions[0])
        self.assertEqual(first.headers["Cache-Control"], "no-store")

        second = self.create()
        self.assertEqual(second.status_code, 200)
        self.assertEqual(
            second.get_json()["job"]["id"], payload["job"]["id"]
        )
        self.assertEqual(len(self.jobs.data), 1)

        stored = self.jobs.get(payload["job"]["id"])
        created_at = datetime.fromisoformat(stored["createdAt"].replace("Z", "+00:00"))
        self.assertEqual(
            stored["expiresAt"] - created_at,
            timedelta(days=30),
        )

    def test_create_backfills_retention_for_an_existing_job(self):
        job = self.created_job()
        del self.jobs.data[job["id"]]["expiresAt"]

        response = self.create()

        self.assertEqual(response.status_code, 200)
        self.assertIsInstance(self.jobs.get(job["id"])["expiresAt"], datetime)

    def test_retention_deadline_is_thirty_days(self):
        now = datetime(2026, 7, 27, 12, 0, tzinfo=timezone.utc)

        self.assertEqual(retention_deadline(now), now + timedelta(days=30))

    def test_idempotency_key_cannot_be_reused_for_different_audio(self):
        self.create()
        response = self.create(byteLength=2048)
        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.get_json()["error"]["code"], "idempotency_conflict")

    def test_create_rejects_an_oversized_audio_upload(self):
        response = self.create(byteLength=256 * 1024 * 1024 + 1)
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.get_json()["error"]["code"], "invalid_byte_length")

    def test_daily_dictation_limit_is_atomic_and_keeps_idempotent_replays(self):
        first = self.create()
        self.assertEqual(first.status_code, 201)
        self.jobs.usage["dictations"] = 50

        replay = self.create()
        blocked = self.create(key="another-recording")

        self.assertEqual(replay.status_code, 200)
        self.assertEqual(blocked.status_code, 429)
        self.assertEqual(blocked.get_json()["error"]["code"], "daily_dictation_limit")

    def test_create_rejects_dormant_server_side_cleanup(self):
        response = self.create(cleanup=True)

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.get_json()["error"]["code"], "invalid_cleanup")

    def test_commit_waits_for_a_finished_upload(self):
        job = self.created_job()
        response = self.client.post(
            f"/v1/dictations/{job['id']}/commit", headers=AUTH
        )
        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.get_json()["error"]["code"], "upload_incomplete")
        self.assertEqual(self.speech.submissions, [])

    def test_commit_submits_speech_once(self):
        job = self.created_job()
        self.storage.finish(job)

        first = self.client.post(
            f"/v1/dictations/{job['id']}/commit", headers=AUTH
        )
        self.assertEqual(first.status_code, 202)
        self.assertEqual(first.get_json()["job"]["state"], "TRANSCRIBING")

        second = self.client.post(
            f"/v1/dictations/{job['id']}/commit", headers=AUTH
        )
        self.assertEqual(second.status_code, 202)
        self.assertEqual(self.speech.submissions, [job["id"]])

    def test_concurrent_commits_have_one_speech_owner(self):
        job = self.created_job()
        self.storage.finish(job)
        self.jobs.get_barrier = threading.Barrier(2)
        statuses = []

        def commit():
            with self.app.test_client() as client:
                response = client.post(
                    f"/v1/dictations/{job['id']}/commit", headers=AUTH
                )
                statuses.append(response.status_code)

        threads = [threading.Thread(target=commit) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=3)
        self.jobs.get_barrier = None

        self.assertFalse(any(thread.is_alive() for thread in threads))
        self.assertEqual(sorted(statuses), [202, 202])
        self.assertEqual(self.speech.submissions, [job["id"]])

    def test_status_recovers_a_stale_submit_claim(self):
        job = self.created_job()
        self.storage.finish(job)
        self.jobs.patch(
            job["id"],
            {
                "state": "SUBMITTING",
                "submitStartedAt": "2000-01-01T00:00:00Z",
            },
        )

        response = self.client.get(f"/v1/dictations/{job['id']}", headers=AUTH)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json()["job"]["state"], "TRANSCRIBING")
        self.assertEqual(self.speech.submissions, [job["id"]])

    def test_status_restores_a_lost_speech_operation_before_resubmitting(self):
        job = self.created_job()
        self.storage.finish(job)
        self.jobs.patch(
            job["id"],
            {
                "state": "SUBMITTING",
                "submitLease": "lost-lease",
                "submitStartedAt": "2000-01-01T00:00:00Z",
            },
        )
        self.speech.recovered_operation = "operations/recovered"

        response = self.client.get(f"/v1/dictations/{job['id']}", headers=AUTH)

        self.assertEqual(response.status_code, 200)
        stored = self.jobs.get(job["id"])
        self.assertEqual(stored["state"], "TRANSCRIBING")
        self.assertEqual(stored["operationName"], "operations/recovered")
        self.assertEqual(self.speech.submissions, [])

    def test_ambiguous_submit_failure_keeps_lease_until_operation_recovery(self):
        job = self.created_job()
        self.storage.finish(job)
        self.speech.submit_error = TimeoutError("response lost")

        submitted = self.client.post(
            f"/v1/dictations/{job['id']}/commit", headers=AUTH
        )

        self.assertEqual(submitted.status_code, 202)
        stored = self.jobs.get(job["id"])
        self.assertEqual(stored["state"], "SUBMITTING")
        self.assertIsNotNone(stored["submitLease"])
        self.assertEqual(self.speech.submissions, [job["id"]])

        self.speech.submit_error = None
        self.speech.recovered_operation = "operations/recovered-after-timeout"
        self.jobs.patch(job["id"], {"submitStartedAt": "2000-01-01T00:00:00Z"})

        recovered = self.client.get(f"/v1/dictations/{job['id']}", headers=AUTH)

        self.assertEqual(recovered.status_code, 200)
        stored = self.jobs.get(job["id"])
        self.assertEqual(stored["state"], "TRANSCRIBING")
        self.assertEqual(
            stored["operationName"], "operations/recovered-after-timeout"
        )
        self.assertEqual(self.speech.submissions, [job["id"]])

    def test_deterministic_speech_submission_error_is_terminal(self):
        class RejectedSpeechRequest(Exception):
            status_code = 400

        job = self.created_job()
        self.storage.finish(job)
        self.speech.submit_error = RejectedSpeechRequest("bad request")

        response = self.client.post(
            f"/v1/dictations/{job['id']}/commit", headers=AUTH
        )
        retry = self.client.post(
            f"/v1/dictations/{job['id']}/retry", headers=AUTH
        )

        self.assertEqual(response.status_code, 202)
        stored = self.jobs.get(job["id"])
        self.assertEqual(stored["state"], "FAILED")
        self.assertEqual(stored["error"]["code"], "speech_submission_rejected")
        self.assertFalse(stored["error"]["retryable"])
        self.assertEqual(retry.status_code, 409)
        self.assertEqual(self.speech.submissions, [job["id"]])

    def test_daily_speech_limit_stops_submission(self):
        job = self.created_job()
        self.storage.finish(job)
        self.jobs.usage["speechSubmissions"] = 75

        response = self.client.post(
            f"/v1/dictations/{job['id']}/commit", headers=AUTH
        )

        self.assertEqual(response.status_code, 202)
        self.assertEqual(response.get_json()["job"]["state"], "FAILED")
        self.assertEqual(
            response.get_json()["job"]["error"]["code"], "daily_speech_limit"
        )
        self.assertEqual(self.speech.submissions, [])

    def test_status_keeps_raw_transcript_when_dictation_cleanup_is_off(self):
        job = self.created_job()
        self.storage.finish(job)
        self.client.post(f"/v1/dictations/{job['id']}/commit", headers=AUTH)
        self.speech.result = {"done": True, "transcript": "literal words"}

        response = self.client.get(f"/v1/dictations/{job['id']}", headers=AUTH)
        payload = response.get_json()["job"]
        self.assertEqual(payload["state"], "READY")
        self.assertEqual(payload["transcript"], "literal words")
        self.assertEqual(payload["rawTranscript"], "literal words")
        self.assertIsNone(payload["polishedTranscript"])

    def test_speech_failure_is_terminal_and_retryable(self):
        job = self.created_job()
        self.storage.finish(job)
        self.client.post(f"/v1/dictations/{job['id']}/commit", headers=AUTH)
        self.speech.result = {
            "done": True,
            "error": {
                "code": "speech_failed",
                "message": "Transcription failed.",
                "retryable": True,
            },
        }

        response = self.client.get(f"/v1/dictations/{job['id']}", headers=AUTH)
        payload = response.get_json()["job"]
        self.assertEqual(payload["state"], "FAILED")
        self.assertTrue(payload["error"]["retryable"])

    def test_missing_speech_result_is_saved_as_a_retryable_failure(self):
        job = self.created_job()
        self.storage.finish(job)
        self.client.post(f"/v1/dictations/{job['id']}/commit", headers=AUTH)
        self.speech.result = {
            "done": True,
            "error": {
                "code": "speech_result_missing",
                "message": "The transcription result is no longer available.",
                "retryable": True,
            },
        }

        response = self.client.get(f"/v1/dictations/{job['id']}", headers=AUTH)

        payload = response.get_json()["job"]
        self.assertEqual(payload["state"], "FAILED")
        self.assertEqual(payload["error"]["code"], "speech_result_missing")
        self.assertTrue(payload["error"]["retryable"])

    def test_missing_speech_result_cannot_overwrite_a_delete_tombstone(self):
        job = self.created_job()
        self.storage.finish(job)
        self.client.post(f"/v1/dictations/{job['id']}/commit", headers=AUTH)
        self.speech.result = {
            "done": True,
            "error": {
                "code": "speech_result_missing",
                "message": "The transcription result is no longer available.",
                "retryable": True,
            },
        }
        original_claim = self.jobs.claim

        def delete_before_failed_claim(job_id, states, values, expected=None):
            if states == {"TRANSCRIBING"} and values.get("state") == "FAILED":
                self.jobs.patch(job_id, {"state": "DELETING"})
            return original_claim(job_id, states, values, expected)

        self.jobs.claim = delete_before_failed_claim

        response = self.client.get(f"/v1/dictations/{job['id']}", headers=AUTH)

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json()["job"]["state"], "DELETING")
        self.assertEqual(self.jobs.get(job["id"])["state"], "DELETING")

    def test_text_generation_replays_its_saved_response(self):
        self.ready_job()
        headers = self.generation_headers()
        first = self.client.post(
            "/v1/text:generate",
            json={
                "text": "raw transcript",
                "prompt": "clean it",
            },
            headers=headers,
        )
        self.config["gemini_model"] = "gemini-new-default"
        second = self.client.post(
            "/v1/text:generate",
            json={
                "text": "raw transcript",
                "prompt": "clean it",
            },
            headers=headers,
        )

        self.assertEqual(first.status_code, 200)
        self.assertEqual(second.status_code, 200)
        self.assertEqual(
            first.get_json(),
            {"text": "generated raw transcript", "model": "gemini-default"},
        )
        self.assertEqual(second.get_json(), first.get_json())
        self.assertEqual(
            self.gemini.calls,
            [("raw transcript", "clean it", "gemini-default")],
        )
        generation = self.jobs.get(
            generation_id_for("only-user", "recording-123", "generation-123")
        )
        self.assertEqual(generation["state"], "READY")
        self.assertEqual(generation["generatedText"], "generated raw transcript")
        self.assertEqual(first.headers["Cache-Control"], "no-store")

    def test_dictation_routes_reject_non_dictation_collection_documents(self):
        self.jobs.data["usage-only-user"] = {
            "id": "usage-only-user",
            "uid": "only-user",
            "kind": "DAILY_USAGE",
            "state": "DAILY_USAGE",
        }

        response = self.client.get(
            "/v1/dictations/usage-only-user",
            headers=AUTH,
        )

        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.get_json()["error"]["code"], "not_found")

    def test_text_generation_requires_an_idempotency_key(self):
        self.ready_job()
        response = self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "clean it"},
            headers={**AUTH, "Recording-Id": "recording-123"},
        )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(
            response.get_json()["error"]["code"], "invalid_idempotency_key"
        )
        self.assertEqual(self.gemini.calls, [])

    def test_text_generation_requires_a_recording_id(self):
        response = self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "clean it"},
            headers={**AUTH, "Idempotency-Key": "generation-123"},
        )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.get_json()["error"]["code"], "invalid_recording_id")
        self.assertEqual(self.gemini.calls, [])

    def test_text_generation_requires_an_owned_ready_dictation(self):
        response = self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "clean it"},
            headers=self.generation_headers(recording_id="missing-recording"),
        )

        self.assertEqual(response.status_code, 404)
        self.assertEqual(self.gemini.calls, [])

    def test_text_generation_rejects_conflicting_idempotency_reuse(self):
        self.ready_job()
        headers = self.generation_headers()
        self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "clean it"},
            headers=headers,
        )

        response = self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "rewrite it"},
            headers=headers,
        )

        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.get_json()["error"]["code"], "idempotency_conflict")
        self.assertEqual(len(self.gemini.calls), 1)

    def test_concurrent_text_generation_has_one_vertex_owner(self):
        self.ready_job()
        self.gemini.generate_started = threading.Event()
        self.gemini.release_generate = threading.Event()
        headers = self.generation_headers()
        first_status = []

        def generate():
            with self.app.test_client() as client:
                response = client.post(
                    "/v1/text:generate",
                    json={"text": "raw transcript", "prompt": "clean it"},
                    headers=headers,
                )
                first_status.append(response.status_code)

        thread = threading.Thread(target=generate)
        thread.start()
        self.assertTrue(self.gemini.generate_started.wait(timeout=3))

        pending = self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "clean it"},
            headers=headers,
        )

        self.assertEqual(pending.status_code, 202)
        self.assertEqual(pending.get_json(), {"state": "GENERATING"})
        self.gemini.release_generate.set()
        thread.join(timeout=3)
        self.assertFalse(thread.is_alive())
        self.assertEqual(first_status, [200])
        self.assertEqual(len(self.gemini.calls), 1)

    def test_ambiguous_text_generation_waits_before_a_bounded_retry(self):
        self.ready_job()
        headers = self.generation_headers()
        self.gemini.generate_error = TimeoutError("vertex timeout")

        failed = self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "clean it"},
            headers=headers,
        )
        self.gemini.generate_error = None
        pending = self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "clean it"},
            headers=headers,
        )
        generation_id = generation_id_for(
            "only-user", "recording-123", "generation-123"
        )
        self.jobs.patch(
            generation_id,
            {"generationStartedAt": "2000-01-01T00:00:00Z"},
        )
        retried = self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "clean it"},
            headers=headers,
        )

        self.assertEqual(failed.status_code, 502)
        self.assertEqual(pending.status_code, 202)
        self.assertEqual(retried.status_code, 200)
        self.assertEqual(len(self.gemini.calls), 2)

    def test_deterministic_text_generation_error_does_not_retry(self):
        class RejectedGeneration(Exception):
            status_code = 400

        self.ready_job()
        headers = self.generation_headers()
        self.gemini.generate_error = RejectedGeneration("bad request")

        failed = self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "clean it"},
            headers=headers,
        )
        retried = self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "clean it"},
            headers=headers,
        )

        self.assertEqual(failed.status_code, 422)
        self.assertEqual(retried.status_code, 422)
        self.assertEqual(len(self.gemini.calls), 1)

    def test_daily_vertex_limit_stops_generation(self):
        self.ready_job()
        self.jobs.usage["vertexRequests"] = 200

        response = self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "clean it"},
            headers=self.generation_headers(),
        )

        self.assertEqual(response.status_code, 429)
        self.assertEqual(response.get_json()["error"]["code"], "daily_vertex_limit")
        self.assertEqual(self.gemini.calls, [])

    def test_text_generation_rejects_a_model_override(self):
        self.ready_job()
        response = self.client.post(
            "/v1/text:generate",
            json={
                "text": "raw transcript",
                "prompt": "clean it",
                "model": "gemini-expensive",
            },
            headers=self.generation_headers(),
        )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.get_json()["error"]["code"], "invalid_model")
        self.assertEqual(self.gemini.calls, [])

    def test_retry_reports_storage_unavailable_when_stale_audio_cannot_be_deleted(self):
        job = self.created_job()
        self.storage.objects[job["objectName"]] = {
            "size": job["byteLength"] + 1,
            "crc32c": job["crc32c"],
            "contentType": job["contentType"],
        }
        self.jobs.patch(
            job["id"],
            {"state": "FAILED", "error": {"code": "upload_invalid", "retryable": True}},
        )
        self.storage.delete_error = True

        response = self.client.post(
            f"/v1/dictations/{job['id']}/retry", headers=AUTH
        )

        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.get_json()["error"]["code"], "storage_unavailable")
        self.assertEqual(self.speech.submissions, [])

    def test_retry_cannot_overwrite_a_racing_delete_tombstone(self):
        job = self.created_job()
        self.jobs.patch(job["id"], {"state": "FAILED"})
        original_claim = self.jobs.claim

        def delete_before_retry_claim(job_id, states, values, expected=None):
            if states == {"FAILED"}:
                self.jobs.patch(job_id, {"state": "DELETING"})
            return original_claim(job_id, states, values, expected)

        self.jobs.claim = delete_before_retry_claim

        response = self.client.post(
            f"/v1/dictations/{job['id']}/retry", headers=AUTH
        )

        self.assertEqual(response.status_code, 409)
        self.assertEqual(
            response.get_json()["error"]["code"], "deletion_in_progress"
        )
        self.assertEqual(self.jobs.get(job["id"])["state"], "DELETING")

    def test_invalid_upload_cannot_overwrite_a_racing_delete_tombstone(self):
        job = self.created_job()
        self.storage.objects[job["objectName"]] = {
            "size": job["byteLength"] + 1,
            "crc32c": job["crc32c"],
            "contentType": job["contentType"],
        }
        original_claim = self.jobs.claim

        def delete_before_invalid_upload_claim(job_id, states, values, expected=None):
            if states == {"AWAITING_UPLOAD"} and values.get("state") == "FAILED":
                self.jobs.patch(job_id, {"state": "DELETING"})
            return original_claim(job_id, states, values, expected)

        self.jobs.claim = delete_before_invalid_upload_claim

        response = self.client.post(
            f"/v1/dictations/{job['id']}/commit", headers=AUTH
        )

        self.assertEqual(response.status_code, 409)
        self.assertEqual(
            response.get_json()["error"]["code"], "deletion_in_progress"
        )
        self.assertEqual(self.jobs.get(job["id"])["state"], "DELETING")

    def test_vertex_sets_a_bounded_request_and_output(self):
        with patch("google.genai.Client") as client_class:
            client_class.return_value.models.generate_content.return_value = (
                SimpleNamespace(text="generated")
            )
            gemini = VertexGemini("test-project", "global", "gemini-default")

            result = gemini.generate("raw", "clean it")

        self.assertEqual(result, ("generated", "gemini-default"))
        http_options = client_class.call_args.kwargs["http_options"]
        self.assertEqual(http_options.timeout, GEMINI_TIMEOUT_MS)
        generation_config = (
            client_class.return_value.models.generate_content.call_args.kwargs["config"]
        )
        self.assertEqual(
            generation_config.max_output_tokens, GEMINI_MAX_OUTPUT_TOKENS
        )

    def test_storage_cancels_only_google_resumable_upload_urls(self):
        response = SimpleNamespace(status_code=499)
        upload_http = SimpleNamespace(delete=lambda *args, **kwargs: response)
        provider = GoogleStorage.__new__(GoogleStorage)
        provider.upload_http = upload_http

        provider.cancel_upload_session(
            "https://storage.googleapis.com/upload/storage/v1/b/bucket/o?upload_id=abc"
        )

        with self.assertRaises(ValueError):
            provider.cancel_upload_session("https://example.com/upload/session")

    def test_delete_clears_cloud_data_and_job_state(self):
        job = self.created_job()
        session_url = job["uploadSessionUrl"]
        self.storage.finish(job)
        response = self.client.delete(f"/v1/dictations/{job['id']}", headers=AUTH)
        self.assertEqual(response.status_code, 204)
        self.assertIsNone(self.jobs.get(job["id"]))
        self.assertEqual(self.storage.deleted, [job["id"], job["id"]])
        self.assertEqual(self.storage.cancelled_sessions, [session_url])

    def test_delete_clears_linked_vertex_generation_records(self):
        job = self.ready_job()
        generation_id = generation_id_for(
            "only-user", "recording-123", "generation-123"
        )
        generated = self.client.post(
            "/v1/text:generate",
            json={"text": "raw transcript", "prompt": "clean it"},
            headers=self.generation_headers(),
        )
        self.assertEqual(generated.status_code, 200)
        self.assertIn(generation_id, self.jobs.get(job["id"])["generationIds"])

        deleted = self.client.delete(f"/v1/dictations/{job['id']}", headers=AUTH)

        self.assertEqual(deleted.status_code, 204)
        self.assertIsNone(self.jobs.get(job["id"]))
        self.assertIsNone(self.jobs.get(generation_id))

    def test_delete_keeps_its_tombstone_when_firestore_is_unavailable(self):
        job = self.created_job()
        self.storage.finish(job)
        self.jobs.delete_error = True

        failed = self.client.delete(f"/v1/dictations/{job['id']}", headers=AUTH)

        self.assertEqual(failed.status_code, 503)
        self.assertEqual(self.jobs.get(job["id"])["state"], "DELETING")

        self.jobs.delete_error = False
        finished = self.client.delete(f"/v1/dictations/{job['id']}", headers=AUTH)
        self.assertEqual(finished.status_code, 204)

    def test_delete_keeps_a_tombstone_until_speech_stops(self):
        job = self.created_job()
        self.storage.finish(job)
        self.client.post(f"/v1/dictations/{job['id']}/commit", headers=AUTH)

        pending = self.client.delete(f"/v1/dictations/{job['id']}", headers=AUTH)

        self.assertEqual(pending.status_code, 202)
        self.assertEqual(pending.get_json()["job"]["state"], "DELETING")
        self.assertEqual(self.speech.cancelled, [f"operations/{job['id']}"])
        self.assertEqual(self.jobs.get(job["id"])["state"], "DELETING")

        self.speech.operation_is_done = True
        finished = self.client.delete(f"/v1/dictations/{job['id']}", headers=AUTH)

        self.assertEqual(finished.status_code, 204)
        self.assertIsNone(self.jobs.get(job["id"]))

    def test_delete_during_submit_keeps_the_returned_operation_on_the_tombstone(self):
        job = self.created_job()
        self.storage.finish(job)
        self.speech.submit_started = threading.Event()
        self.speech.release_submit = threading.Event()
        status = []

        def commit():
            with self.app.test_client() as client:
                response = client.post(
                    f"/v1/dictations/{job['id']}/commit", headers=AUTH
                )
                status.append(response.status_code)

        thread = threading.Thread(target=commit)
        thread.start()
        self.assertTrue(self.speech.submit_started.wait(timeout=3))

        pending = self.client.delete(f"/v1/dictations/{job['id']}", headers=AUTH)
        self.assertEqual(pending.status_code, 202)

        self.speech.release_submit.set()
        thread.join(timeout=3)
        self.assertFalse(thread.is_alive())
        self.assertEqual(status, [202])
        stored = self.jobs.get(job["id"])
        self.assertEqual(stored["state"], "DELETING")
        self.assertEqual(stored["operationName"], f"operations/{job['id']}")

        self.speech.operation_is_done = True
        finished = self.client.delete(f"/v1/dictations/{job['id']}", headers=AUTH)
        self.assertEqual(finished.status_code, 204)

    def test_speech_result_uri_prefers_native_and_keeps_compatibility_fallbacks(self):
        native = SimpleNamespace(
            cloud_storage_result=SimpleNamespace(
                native_format_uri="gs://bucket/native.json",
                uri="gs://bucket/cloud.json",
            ),
            uri="gs://bucket/legacy.json",
        )
        cloud = SimpleNamespace(
            cloud_storage_result=SimpleNamespace(uri="gs://bucket/cloud.json"),
            uri="gs://bucket/legacy.json",
        )
        legacy = SimpleNamespace(cloud_storage_result=None, uri="gs://bucket/legacy.json")
        self.assertEqual(batch_result_uri(native), "gs://bucket/native.json")
        self.assertEqual(batch_result_uri(cloud), "gs://bucket/cloud.json")
        self.assertEqual(batch_result_uri(legacy), "gs://bucket/legacy.json")

    def test_speech_poll_decodes_the_real_long_running_operation_shape(self):
        from google.cloud.speech_v2.types import cloud_speech
        from google.longrunning.operations_pb2 import Operation
        from google.protobuf.any_pb2 import Any

        input_uri = "gs://test-bucket/audio/job.m4a"
        output_uri = "gs://test-bucket/results/job/result.json"
        results = cloud_speech.BatchRecognizeResults(
            results=[
                cloud_speech.SpeechRecognitionResult(
                    alternatives=[
                        cloud_speech.SpeechRecognitionAlternative(transcript="hello world")
                    ]
                )
            ]
        )
        response = cloud_speech.BatchRecognizeResponse(
            results={
                input_uri: cloud_speech.BatchRecognizeFileResult(
                    cloud_storage_result=cloud_speech.CloudStorageResult(uri=output_uri)
                )
            }
        )
        packed = Any()
        packed.Pack(cloud_speech.BatchRecognizeResponse.pb(response))
        operation = Operation(name="operations/job", done=True, response=packed)
        operations = SimpleNamespace(
            get_operation=lambda name, timeout: operation
        )

        provider = GoogleSpeech.__new__(GoogleSpeech)
        provider.types = cloud_speech
        provider.bucket = "test-bucket"
        provider.client = SimpleNamespace(
            transport=SimpleNamespace(operations_client=operations)
        )
        provider.storage = SimpleNamespace(
            read_text=lambda uri: cloud_speech.BatchRecognizeResults.to_json(results)
        )

        result = provider.poll(
            {"operationName": "operations/job", "objectName": "audio/job.m4a"}
        )
        self.assertEqual(result, {"done": True, "transcript": "hello world"})

    def test_speech_poll_marks_a_missing_result_retryable_when_audio_remains(self):
        from google.api_core.exceptions import NotFound
        from google.cloud.speech_v2.types import cloud_speech
        from google.longrunning.operations_pb2 import Operation
        from google.protobuf.any_pb2 import Any

        input_uri = "gs://test-bucket/audio/job.m4a"
        output_uri = "gs://test-bucket/results/job/result.json"
        response = cloud_speech.BatchRecognizeResponse(
            results={
                input_uri: cloud_speech.BatchRecognizeFileResult(
                    cloud_storage_result=cloud_speech.CloudStorageResult(uri=output_uri)
                )
            }
        )
        packed = Any()
        packed.Pack(cloud_speech.BatchRecognizeResponse.pb(response))
        operation = Operation(name="operations/job", done=True, response=packed)

        def missing_result(uri):
            raise NotFound(f"missing {uri}")

        provider = GoogleSpeech.__new__(GoogleSpeech)
        provider.types = cloud_speech
        provider.bucket = "test-bucket"
        provider.client = SimpleNamespace(
            transport=SimpleNamespace(
                operations_client=SimpleNamespace(
                    get_operation=lambda name, timeout: operation
                )
            )
        )
        provider.storage = SimpleNamespace(
            read_text=missing_result,
            object_info=lambda object_name: {"size": 1024},
        )

        result = provider.poll(
            {"operationName": "operations/job", "objectName": "audio/job.m4a"}
        )

        self.assertEqual(
            result,
            {
                "done": True,
                "error": {
                    "code": "speech_result_missing",
                    "message": "The transcription result is no longer available.",
                    "retryable": True,
                },
            },
        )

    def test_speech_finds_the_newest_lost_operation_by_its_input_and_output(self):
        from google.cloud.speech_v2.types import cloud_speech
        from google.longrunning.operations_pb2 import Operation
        from google.protobuf.any_pb2 import Any

        request = cloud_speech.BatchRecognizeRequest(
            files=[
                cloud_speech.BatchRecognizeFileMetadata(
                    uri="gs://test-bucket/audio/job.m4a"
                )
            ],
            recognition_output_config=cloud_speech.RecognitionOutputConfig(
                gcs_output_config=cloud_speech.GcsOutputConfig(
                    uri="gs://test-bucket/results/job/"
                )
            ),
        )
        def operation(name, created_at):
            metadata = cloud_speech.OperationMetadata(
                create_time=created_at,
                batch_recognize_request=request,
            )
            packed = Any()
            packed.Pack(cloud_speech.OperationMetadata.pb(metadata))
            return Operation(name=name, metadata=packed)

        old_operation = operation(
            "operations/old-failed",
            datetime(2026, 7, 27, 12, 1, tzinfo=timezone.utc),
        )
        newest_operation = operation(
            "operations/newest",
            datetime(2026, 7, 27, 12, 2, tzinfo=timezone.utc),
        )
        operations = SimpleNamespace(
            list_operations=lambda name, filter_, timeout: [
                old_operation,
                newest_operation,
            ]
        )

        provider = GoogleSpeech.__new__(GoogleSpeech)
        provider.types = cloud_speech
        provider.project = "test-project"
        provider.location = "us"
        provider.bucket = "test-bucket"
        provider.client = SimpleNamespace(
            transport=SimpleNamespace(operations_client=operations)
        )

        recovered = provider.find_operation(
            {
                "id": "job",
                "objectName": "audio/job.m4a",
                "submitStartedAt": "2026-07-27T12:00:00Z",
            }
        )

        self.assertEqual(recovered, "operations/newest")


if __name__ == "__main__":
    unittest.main()
