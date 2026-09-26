# Build context is the repo root (not this directory) — see .github/workflows/deploy-cloudrun.yml's
# `dockerfile` input and docker-compose.yml. That is what lets this image COPY in the sibling
# conductor-creative/ package the frontend depends on via "@cliangdev/creative-render":
# "file:../conductor-creative" (resolved relative to /app/package.json, i.e. to /conductor-creative
# below). Building with conductor-frontend/ alone as context, as this Dockerfile used to, cannot see
# that sibling directory at all.

# Stage 1: Dependencies (cached layer)
FROM node:22-alpine AS deps
WORKDIR /app
COPY conductor-frontend/package.json conductor-frontend/package-lock.json* ./
COPY conductor-creative /conductor-creative
RUN npm ci

# Stage 2: Dev (HMR via volume mount)
FROM deps AS dev
COPY conductor-frontend/. .
EXPOSE 3000
CMD ["npm", "run", "dev"]

# Stage 3: Builder
FROM deps AS builder
COPY conductor-frontend/. .
ARG NEXT_PUBLIC_FIREBASE_API_KEY
ARG NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN
ARG NEXT_PUBLIC_FIREBASE_PROJECT_ID
ARG NEXT_PUBLIC_API_URL
ARG NEXT_PUBLIC_SITE_URL
ENV NEXT_PUBLIC_FIREBASE_API_KEY=$NEXT_PUBLIC_FIREBASE_API_KEY
ENV NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=$NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN
ENV NEXT_PUBLIC_FIREBASE_PROJECT_ID=$NEXT_PUBLIC_FIREBASE_PROJECT_ID
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
ENV NEXT_PUBLIC_SITE_URL=$NEXT_PUBLIC_SITE_URL
RUN npm run build

# Stage 4: Production runtime
#
# next.config.mjs sets `turbopack.root` one directory above conductor-frontend (the repo root, so
# Turbopack's module resolution covers the sibling conductor-creative/ symlink target too — see that
# file's comment). A side effect: Next's standalone output nests the server under the traced
# project's path *relative to that root* instead of at the standalone folder's own top level — here,
# relative to "/" (root) from a WORKDIR of "/app", that path is exactly "app". If the WORKDIR above
# ever changes, this nested path must change with it.
FROM node:22-alpine AS prod
WORKDIR /app
ENV NODE_ENV=production
COPY --from=builder /app/.next/standalone/app ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public/ ./public/
EXPOSE 3000
CMD ["node", "server.js"]
