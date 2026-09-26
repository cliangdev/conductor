const doc = `## What it does

Publishes images and video to a **Facebook Page** and, when one is linked, an **Instagram Business or Creator** account. A single Post can target both, and a workspace can connect several Meta accounts — each publishes only through its own connection.

## Before you connect

Meta publishes through Conductor's own reviewed app, so there is no app to create, no business verification to complete, and no App Review to submit. Make sure you have the accounts below, then click **Connect** and log in with a Facebook account that administers the Page.

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

Two things worth watching rather than trusting this page: whether \`instagram_basic\` and \`instagram_content_publish\` are being sunset in favour of the newer \`instagram_business_*\` names, and whether a Page video published through the standard endpoint is reclassified as a Reel. Neither is settled in Meta's public documentation.
`

export default doc
