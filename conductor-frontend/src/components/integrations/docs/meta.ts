const doc = `## What it does

Publishes images and video to a **Facebook Page** and, when one is linked, an **Instagram Business or Creator** account. A single Post can target both, and a workspace can connect several Meta accounts — each publishes only through its own connection.

## Before you connect

Meta has the longest lead time of any integration here. Start it early.

1. Create a **Business** app in the Meta App Dashboard.
2. Add **Facebook Login for Business**, and **Instagram → Instagram API setup with Facebook login**.
3. Register the redirect URI under *Facebook Login → Settings → Valid OAuth Redirect URIs*: \`{BACKEND_URL}/api/v1/oauth/callback\`.
4. Complete **business verification**. This is a separate gate that blocks App Review submission entirely, and commonly takes over a week — start it in parallel, not afterwards.
5. Submit **App Review** for \`pages_manage_posts\`, \`pages_read_engagement\`, \`instagram_basic\`, \`instagram_content_publish\` and \`business_management\`. Only \`pages_show_list\` needs no review. Budget several weeks including a resubmission.

**You can test the whole flow before review finishes.** Review is only required once someone *without a role on the app* uses it. Add your own Facebook account — and the Page and Instagram account you intend to publish to — as an app **Tester**, and every permission works immediately.

## Account requirements

- A Facebook **Page**. Personal profiles have no publishing API at any level of effort.
- Instagram must be a **Business or Creator** account linked to that Page. Personal Instagram accounts cannot publish via API.

Conductor uses the Page-linked path (Facebook Login). Meta also offers a newer *Instagram API with Instagram Login* that needs no Page — that is a different authentication architecture with different permission names, not a setting you toggle.

## How authentication works

Connecting runs an OAuth flow, exchanges the short-lived token for a long-lived one, lists the Pages you administer, and asks which to use. Conductor stores that Page's access token plus the linked Instagram account id.

A Page access token does not expire on a timer, but it dies immediately if the authorizing user changes their Facebook password, loses their role on the Page, or revokes the app. When that happens the connection shows as needing reconnection — treat it as a permissions signal, not an expiry.

## Limits and behaviour

- **Instagram: 100 API posts per rolling 24 hours, per account.** Feed images must be JPEG with an aspect ratio between 4:5 and 1.91:1 — Conductor checks this at approval rather than at publish time.
- **Instagram has no native scheduling.** Conductor holds the approved post and publishes at the fire time.
- **Facebook schedules natively.** Conductor hands the post to Meta with \`scheduled_publish_time\` and Meta fires it. The documented window for feed and photo posts is 10 minutes to 30 days; Meta's video reference states 10 minutes to 6 months. Conductor applies the narrower window, so a post scheduled further out waits and is handed off once it comes inside range — it still goes live at the time you chose.
- Video containers are processed asynchronously; Conductor polls until Meta reports the media ready before publishing.

## Not yet confirmed

Two things worth verifying against your own app dashboard rather than trusting this page: whether \`instagram_basic\` and \`instagram_content_publish\` are being sunset in favour of the newer \`instagram_business_*\` names, and whether a Page video published through the standard endpoint is reclassified as a Reel. Neither is settled in Meta's public documentation.
`

export default doc
