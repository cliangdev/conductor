const doc = `## What it does

Posts video to a **TikTok creator account**. TikTok has no scheduling API, so Conductor holds the approved video and posts it at the fire time.

## Before you connect

1. Register an app in the TikTok for Developers console to get a **client key** and secret.
2. Add the **Content Posting API** product and enable **Direct Post** — a separate toggle from adding the product itself. Posting will not work without it.
3. Add the **Login Kit** product. Redirect URIs are registered here, not under Content Posting API: \`{BACKEND_URL}/api/v1/oauth/callback\`. They must be https, absolute, and free of query strings.
4. Request the \`user.info.basic\`, \`video.publish\` and \`video.upload\` scopes. All three need TikTok's approval before they can be granted.
5. Submit the app for TikTok's **content-posting audit**.

> **Until the audit passes, posts are forced to \`SELF_ONLY\`** — visible to the creator alone — and at most **5 users can post in any 24-hour window**. TikTok publishes no timeline for the audit. The most commonly reported rejection reason is a consent flow that doesn't match TikTok's required disclosure screens.

## Compliance requirements

TikTok imposes obligations on the posting experience itself, and audit approval depends on them:

- The creator must see a **preview of the content and the account nickname** it will post to, and must **expressly consent**, before anything is uploaded.
- Commercial content must be disclosable — "Your Brand" (promotional content) and "Branded Content" (paid partnership) are distinct toggles.
- **Branded content cannot be posted privately.** That combination is rejected.
- Privacy level must be one of the options TikTok reports for that specific creator, which differ between public and private accounts. Sending anything else fails.

## How authentication works

TikTok deviates from standard OAuth2 in two ways Conductor handles for you: the client parameter is named \`client_key\` rather than \`client_id\`, and the scope list is comma-separated rather than space-separated.

On connecting, Conductor reads the creator's profile and caches the nickname, the available privacy levels, and the **maximum video duration allowed for that creator** — this is per-account, not a fixed platform limit, and Conductor validates against it at approval.

## Limits and behaviour

- Video files up to **4GB**, uploaded in chunks of 5–64MB. Conductor checkpoints progress so a retry resumes rather than restarting.
- The upload URL is valid for **one hour** from the start of the transfer.
- Two different caps produce similar-looking rejections: the unaudited 5-users-per-24h ceiling, and a per-creator daily posting limit. The error messages distinguish them.
- Conductor uploads the file directly rather than giving TikTok a link to fetch, because pulling from a URL requires verifying ownership of the source domain — impossible for cloud storage.
- Publishing is asynchronous: accepting the upload is not the same as the post going live, so Conductor polls until TikTok reports the outcome.

## Not yet confirmed

TikTok's API commonly returns HTTP 200 with the real outcome in the response body, so Conductor always reads the body rather than trusting the status code. TikTok's documentation does not state this explicitly — it is an operational assumption from observed behaviour.
`

export default doc
