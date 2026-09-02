const doc = `## What it does

Uploads video to a **YouTube channel**, optionally scheduled — Conductor uploads the video as private with a publish time, and YouTube flips it public at that moment. A workspace can connect several channels.

## Before you connect

1. In Google Cloud Console, create or select a project and enable the **YouTube Data API v3**.
2. Create an OAuth 2.0 Client ID of type **Web application**, and register \`{BACKEND_URL}/api/v1/oauth/callback\` under *Authorized redirect URIs*. Mismatches here — a trailing slash, http vs https — are the most common setup failure.
3. Configure the consent screen (*APIs & Services → Google Auth Platform*) with a public homepage and a privacy policy on the same domain that names the scopes you request. A vague or missing privacy policy is the most common verification rejection.
4. Request **OAuth verification** for the \`youtube.upload\` and \`youtube.readonly\` scopes. Until it passes, only 100 users total can authorize the app.
5. Request a **YouTube API audit** using the *API Services — Audit and Quota Extension* form. See below; this one matters more than it looks.

> **The audit is the thing that will catch you out.** Videos uploaded by an unaudited API project are **locked to private**, server-side, regardless of the privacy status your request asks for. There is no error and no appeal — the upload succeeds, the schedule is accepted, and the video simply never goes public. Google gives no timeline; developer reports range from weeks to months. Start it before you need it, and confirm with a real scheduled test video before relying on it.

## Account requirements

The authorizing Google account must actually own or manage a channel. A brand-new account with no channel returns an empty channel list, which looks like a broken integration but isn't.

## How authentication works

Standard Google OAuth. Conductor requests \`youtube.upload\` and \`youtube.readonly\`, then resolves the channel via \`channels.list\` and stores its id and title.

The OAuth client is shared with Conductor's other Google integrations (Search Console, GCP Billing) — adding YouTube's scopes to it means that client goes through verification, which affects those integrations too.

## Limits and behaviour

- **Quota changed substantially and recently.** A video upload used to cost ~1600 units against a shared 10,000/day pool, giving roughly six uploads a day. Since December 2025 uploads draw on their own dedicated bucket of about **100 per day**. Check Google's live quota page rather than trusting any fixed number, including this one — Google has described the change as an ongoing transition. Note also that upload quota no longer shows up in general quota monitoring.
- **Scheduling**: a video must be uploaded as private for a publish time to apply, which is what Conductor does. A time in the past publishes immediately. Google documents no maximum how-far-ahead limit.
- **Shorts**: a vertical (9:16) or square video of three minutes or less is automatically classified as a Short, regardless of any metadata. Conductor warns at approval rather than blocking, since this is usually intended.
- Uploads are resumable and Conductor checkpoints progress, so a retry resumes rather than restarting.
`

export default doc
