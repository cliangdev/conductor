const doc = `## What it does

Posts video to a **TikTok creator account**. TikTok has no scheduling API, so Conductor holds the approved video and posts it at the fire time.

## Before you connect

TikTok publishes through Conductor's own reviewed app, so there is no app to register, no product to add, and no redirect URI to enter. You need a **TikTok account** you can log into with content-posting permission on it. Click **Connect** and log in with that account when TikTok asks.

> **Until Conductor's app passes TikTok's content-posting audit, posts are forced to \`SELF_ONLY\`** (visible to the creator alone), and at most **5 users can post in any 24-hour window**, across every workspace on this deployment. TikTok publishes no timeline for the audit.

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
