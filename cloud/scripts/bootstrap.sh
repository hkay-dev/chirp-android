#!/usr/bin/env bash
set -euo pipefail

: "${PROJECT_ID:?Set PROJECT_ID to a billing-linked Google Cloud project}"
: "${GCS_BUCKET:?Set GCS_BUCKET to a globally unique bucket name}"

REGION="${REGION:-us-west1}"
FIRESTORE_COLLECTION="${FIRESTORE_COLLECTION:-dictations}"
ALLOW_EXISTING_CHIRP_BUCKET="${ALLOW_EXISTING_CHIRP_BUCKET:-false}"
RUNTIME_SERVICE_ACCOUNT_ID="chirp-api"
RUNTIME_SERVICE_ACCOUNT="${RUNTIME_SERVICE_ACCOUNT_ID}@${PROJECT_ID}.iam.gserviceaccount.com"
BUILD_SERVICE_ACCOUNT_ID="chirp-build"
BUILD_SERVICE_ACCOUNT="${BUILD_SERVICE_ACCOUNT_ID}@${PROJECT_ID}.iam.gserviceaccount.com"
SPEECH_CANCEL_ROLE_ID="chirpSpeechCanceller"
BUCKET_OWNERSHIP_LABEL="chirp_private_api"

if [[ "$ALLOW_EXISTING_CHIRP_BUCKET" != "false" && "$ALLOW_EXISTING_CHIRP_BUCKET" != "true" ]]; then
  echo "ALLOW_EXISTING_CHIRP_BUCKET must be true or false." >&2
  exit 1
fi

BETA_COMPONENT_STATE="$(
  gcloud components list \
    --filter='id:beta' \
    --format='value(state.name)' \
    --quiet 2>/dev/null
)"
if [[ "$BETA_COMPONENT_STATE" != "Installed" ]]; then
  echo "The gcloud beta component is needed to create the Speech service identity." >&2
  echo "Run: gcloud components install beta" >&2
  exit 1
fi

gcloud services enable \
  aiplatform.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com \
  firestore.googleapis.com \
  identitytoolkit.googleapis.com \
  run.googleapis.com \
  speech.googleapis.com \
  storage.googleapis.com \
  --project "$PROJECT_ID"

if ! gcloud iam service-accounts describe "$RUNTIME_SERVICE_ACCOUNT" --project "$PROJECT_ID" >/dev/null 2>&1; then
  gcloud iam service-accounts create "$RUNTIME_SERVICE_ACCOUNT_ID" \
    --display-name="Chirp private API" \
    --project "$PROJECT_ID"
fi

if ! gcloud iam service-accounts describe "$BUILD_SERVICE_ACCOUNT" --project "$PROJECT_ID" >/dev/null 2>&1; then
  gcloud iam service-accounts create "$BUILD_SERVICE_ACCOUNT_ID" \
    --display-name="Chirp source builder" \
    --project "$PROJECT_ID"
fi

if gcloud firestore databases describe --database='(default)' --project "$PROJECT_ID" >/dev/null 2>&1; then
  FIRESTORE_TYPE="$(
    gcloud firestore databases describe \
      --database='(default)' \
      --project "$PROJECT_ID" \
      --format='value(type)'
  )"
  if [[ "$FIRESTORE_TYPE" != "FIRESTORE_NATIVE" && "$FIRESTORE_TYPE" != "firestore-native" ]]; then
    echo "The default Firestore database must use Native mode, not $FIRESTORE_TYPE." >&2
    exit 1
  fi
else
  gcloud firestore databases create \
    --database='(default)' \
    --location="$REGION" \
    --type=firestore-native \
    --project "$PROJECT_ID"
fi

PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"

if gcloud storage buckets describe "gs://$GCS_BUCKET" --project "$PROJECT_ID" >/dev/null 2>&1; then
  BUCKET_PROJECT_NUMBER="$(
    gcloud storage buckets describe "gs://$GCS_BUCKET" \
      --project "$PROJECT_ID" \
      --format='value(projectNumber)'
  )"
  BUCKET_LOCATION="$(
    gcloud storage buckets describe "gs://$GCS_BUCKET" \
      --project "$PROJECT_ID" \
      --format='value(location)'
  )"
  BUCKET_OWNER="$(
    gcloud storage buckets describe "gs://$GCS_BUCKET" \
      --project "$PROJECT_ID" \
      --format="value(labels.${BUCKET_OWNERSHIP_LABEL})"
  )"
  if [[ "$BUCKET_PROJECT_NUMBER" != "$PROJECT_NUMBER" ]]; then
    echo "The bucket belongs to project number $BUCKET_PROJECT_NUMBER, not $PROJECT_NUMBER." >&2
    exit 1
  fi
  if [[ "$BUCKET_LOCATION" != "US" ]]; then
    echo "The bucket must use the US multi-region, not $BUCKET_LOCATION." >&2
    exit 1
  fi
  if [[ "$BUCKET_OWNER" != "true" && "$ALLOW_EXISTING_CHIRP_BUCKET" != "true" ]]; then
    echo "The bucket is not marked as Chirp-owned." >&2
    echo "Use a dedicated bucket or rerun once with ALLOW_EXISTING_CHIRP_BUCKET=true to claim it." >&2
    exit 1
  fi
else
  gcloud storage buckets create "gs://$GCS_BUCKET" \
    --location=US \
    --uniform-bucket-level-access \
    --public-access-prevention \
    --soft-delete-duration=0 \
    --project "$PROJECT_ID"
fi

gcloud storage buckets update "gs://$GCS_BUCKET" \
  --lifecycle-file="$(dirname "$0")/../bucket-lifecycle.json" \
  --uniform-bucket-level-access \
  --public-access-prevention \
  --clear-soft-delete \
  --no-versioning \
  --update-labels="${BUCKET_OWNERSHIP_LABEL}=true" \
  --project "$PROJECT_ID"

if gcloud firestore fields ttls update --help >/dev/null 2>&1; then
  gcloud firestore fields ttls update expiresAt \
    --collection-group="$FIRESTORE_COLLECTION" \
    --database='(default)' \
    --enable-ttl \
    --project "$PROJECT_ID" \
    --quiet
else
  echo "Warning: this gcloud CLI cannot configure Firestore TTL; enable TTL on $FIRESTORE_COLLECTION.expiresAt manually." >&2
fi

if gcloud iam roles describe "$SPEECH_CANCEL_ROLE_ID" --project "$PROJECT_ID" >/dev/null 2>&1; then
  gcloud iam roles update "$SPEECH_CANCEL_ROLE_ID" \
    --project "$PROJECT_ID" \
    --title="Chirp Speech canceller" \
    --description="Cancel Chirp Speech-to-Text operations during deletion" \
    --permissions=speech.operations.cancel \
    --stage=GA
else
  gcloud iam roles create "$SPEECH_CANCEL_ROLE_ID" \
    --project "$PROJECT_ID" \
    --title="Chirp Speech canceller" \
    --description="Cancel Chirp Speech-to-Text operations during deletion" \
    --permissions=speech.operations.cancel \
    --stage=GA
fi

for role in \
  roles/aiplatform.user \
  roles/datastore.user \
  roles/firebaseauth.viewer \
  roles/speech.client \
  "projects/${PROJECT_ID}/roles/${SPEECH_CANCEL_ROLE_ID}"
do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:$RUNTIME_SERVICE_ACCOUNT" \
    --role="$role" \
    --condition=None \
    >/dev/null
done

gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:$BUILD_SERVICE_ACCOUNT" \
  --role=roles/run.builder \
  --condition=None \
  >/dev/null

gcloud storage buckets add-iam-policy-binding "gs://$GCS_BUCKET" \
  --member="serviceAccount:$RUNTIME_SERVICE_ACCOUNT" \
  --role=roles/storage.objectUser \
  --condition=None \
  --project "$PROJECT_ID" \
  >/dev/null

gcloud beta services identity create \
  --service=speech.googleapis.com \
  --project "$PROJECT_ID" \
  >/dev/null

gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:service-${PROJECT_NUMBER}@gcp-sa-speech.iam.gserviceaccount.com" \
  --role=roles/speech.serviceAgent \
  --condition=None \
  >/dev/null

echo "Cloud resources are ready in $PROJECT_ID ($REGION)."
