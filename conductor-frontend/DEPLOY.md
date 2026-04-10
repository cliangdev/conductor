# Frontend Deployment Guide

One-time GCP setup for the `conductor-frontend` Cloud Run service.

## Prerequisites

- Same WIF pool, provider, service account, and Artifact Registry repository as the backend. Complete the setup in `conductor-backend/DEPLOY.md` before proceeding.
- `NEXT_PUBLIC_API_URL` requires the Cloud Run URL for `conductor-backend`, which is available after that service is deployed.

## 1. Initial Cloud Run Deployment

```bash
gcloud run deploy conductor-frontend \
  --image=us-central1-docker.pkg.dev/{PROJECT_ID}/conductor/frontend:latest \
  --region=us-central1 \
  --platform=managed \
  --allow-unauthenticated \
  --min-instances=1 \
  --port=3000
```

## 2. Set Environment Variables

These are public Firebase config values and should be passed as plain environment variables, not Secret Manager secrets.

```bash
gcloud run services update conductor-frontend \
  --region=us-central1 \
  --set-env-vars="NEXT_PUBLIC_API_URL=https://conductor-backend-XXXX-uc.a.run.app,\
NEXT_PUBLIC_FIREBASE_API_KEY=AIza...,\
NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=your-project.firebaseapp.com,\
NEXT_PUBLIC_FIREBASE_PROJECT_ID=your-project-id,\
NEXT_PUBLIC_FIREBASE_APP_ID=1:..."
```

Replace `NEXT_PUBLIC_API_URL` with the actual Cloud Run URL from the deployed `conductor-backend` service. Retrieve it with:

```bash
gcloud run services describe conductor-backend \
  --region=us-central1 \
  --format="value(status.url)"
```

## 3. GitHub Actions Configuration

The frontend deploy job reuses the same credentials as the backend. No additional secrets are needed beyond what is already configured.

**Variables** (Settings → Secrets and variables → Actions → Variables):

| Name | Example value |
|------|---------------|
| `GCP_PROJECT_ID` | `my-gcp-project` |
| `GCP_REGION` | `us-central1` |

**Secrets** (Settings → Secrets and variables → Actions → Secrets):

| Name | Value |
|------|-------|
| `WIF_PROVIDER` | Full WIF provider resource name |
| `WIF_SERVICE_ACCOUNT` | `conductor-cd@{PROJECT_ID}.iam.gserviceaccount.com` |

After this setup, every push to `main` that touches `conductor-frontend/**` will automatically build, push, and deploy via `.github/workflows/frontend.yml`.
