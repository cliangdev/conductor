# Backend Deployment Guide

One-time GCP setup for the `conductor-backend` Cloud Run service.

## Prerequisites

- GCP project with billing enabled
- `gcloud` CLI installed and authenticated (`gcloud auth login`)
- Project ID set: `gcloud config set project {PROJECT_ID}`

## 1. Enable Required APIs

```bash
gcloud services enable \
  artifactregistry.googleapis.com \
  run.googleapis.com \
  iam.googleapis.com \
  secretmanager.googleapis.com
```

## 2. Create Artifact Registry Repository

```bash
gcloud artifacts repositories create conductor \
  --repository-format=docker \
  --location=us-central1
```

## 3. Create Service Account

```bash
gcloud iam service-accounts create conductor-cd \
  --display-name="Conductor CD"

gcloud projects add-iam-policy-binding {PROJECT_ID} \
  --member="serviceAccount:conductor-cd@{PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/run.admin"

gcloud projects add-iam-policy-binding {PROJECT_ID} \
  --member="serviceAccount:conductor-cd@{PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.writer"

gcloud projects add-iam-policy-binding {PROJECT_ID} \
  --member="serviceAccount:conductor-cd@{PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountUser"
```

## 4. Workload Identity Federation

Enables GitHub Actions to authenticate without long-lived service account keys.

```bash
gcloud iam workload-identity-pools create github-pool \
  --location=global \
  --display-name="GitHub Actions Pool"

gcloud iam workload-identity-pools providers create-oidc github-provider \
  --location=global \
  --workload-identity-pool=github-pool \
  --display-name="GitHub Provider" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository=='cliangdev/conductor'" \
  --issuer-uri="https://token.actions.githubusercontent.com"

# Bind the pool to the service account
gcloud iam service-accounts add-iam-policy-binding conductor-cd@{PROJECT_ID}.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/{PROJECT_NUMBER}/locations/global/workloadIdentityPools/github-pool/attribute.repository/cliangdev/conductor"
```

To get `{PROJECT_NUMBER}`:

```bash
gcloud projects describe {PROJECT_ID} --format="value(projectNumber)"
```

## 5. Store Secrets in Secret Manager

```bash
# Repeat for each secret below:
echo -n "value" | gcloud secrets create SECRET_NAME --data-file=-

# Required secrets:
# DATABASE_URL
# JWT_SECRET
# FIREBASE_SERVICE_ACCOUNT_KEY
# RESEND_API_KEY
# GCP_SERVICE_ACCOUNT_KEY
# GCP_STORAGE_BUCKET_NAME
# FRONTEND_URL
```

## 6. Grant Secret Manager Access to Cloud Run SA

```bash
gcloud projects add-iam-policy-binding {PROJECT_ID} \
  --member="serviceAccount:conductor-cd@{PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

## 7. Initial Cloud Run Deployment

Build and push the image manually for the first deploy, then run:

```bash
gcloud run deploy conductor-backend \
  --image=us-central1-docker.pkg.dev/{PROJECT_ID}/conductor/backend:latest \
  --region=us-central1 \
  --platform=managed \
  --no-allow-unauthenticated \
  --set-secrets="DATABASE_URL=DATABASE_URL:latest,APP_JWT_SECRET=APP_JWT_SECRET:latest,FIREBASE_SERVICE_ACCOUNT_KEY=FIREBASE_SERVICE_ACCOUNT_KEY:latest,RESEND_API_KEY=RESEND_API_KEY:latest,GCP_SERVICE_ACCOUNT_KEY=GCP_SERVICE_ACCOUNT_KEY:latest,GCP_STORAGE_BUCKET_NAME=GCP_STORAGE_BUCKET_NAME:latest,FRONTEND_URL=FRONTEND_URL:latest"
```

## 8. GitHub Actions Configuration

Add the following in the GitHub repository settings.

**Variables** (Settings → Secrets and variables → Actions → Variables):

| Name | Example value |
|------|---------------|
| `GCP_PROJECT_ID` | `my-gcp-project` |
| `GCP_REGION` | `us-central1` |

**Secrets** (Settings → Secrets and variables → Actions → Secrets):

| Name | Value |
|------|-------|
| `WIF_PROVIDER` | Full resource name of the WIF provider (e.g. `projects/{PROJECT_NUMBER}/locations/global/workloadIdentityPools/github-pool/providers/github-provider`) |
| `WIF_SERVICE_ACCOUNT` | SA email (e.g. `conductor-cd@{PROJECT_ID}.iam.gserviceaccount.com`) |

After this setup, every push to `main` that touches `conductor-backend/**` will automatically build, push, and deploy via `.github/workflows/backend.yml`.
