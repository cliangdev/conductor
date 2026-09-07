# Testing publishing against live Facebook, Instagram and TikTok

Everything in the publishing pipeline has been exercised against stub connectors and a real browser,
but nothing has yet published to a real Facebook Page, Instagram account or TikTok creator. This is
the checklist for doing that once, end to end, and knowing what "working" looks like at each step.
[`publishing.md`](publishing.md) explains the pipeline; this only says how to prove it.

## Where to run it

Run it in **production**, in a workspace created for the purpose (call it *Publishing Lab*) with test
Pages and accounts you control. Two reasons it cannot be a laptop:

- Meta and TikTok fetch media from a URL Conductor hands them (a signed storage URL, valid for
  `GCP_SIGNED_URL_EXPIRY_MINUTES`, 15 by default). A laptop's `LOCAL_STORAGE_PATH` is not reachable.
- OAuth consent redirects to `https://<backend>/api/v1/oauth/callback` (`BACKEND_URL`), which each
  platform app must list as an allowed redirect. The production backend URL is the one to register.

Do not use a PR preview deploy for this: its migrations and rows land in the production database.

Keep the workspace to the people doing the test. Every automated destination publishes for real.

## Before you start: the platform side

### Meta (Facebook Page + Instagram)

1. A Facebook **Page** you administer, and an Instagram **Business or Creator** account linked to that
   Page (Page settings → Linked accounts). Instagram publishing works only through the linked Page.
2. A **Meta app** (developers.facebook.com, type *Business*) with the *Facebook Login for Business*
   and *Instagram* products added. Under *Facebook Login → Settings*, add the redirect URI above.
3. The app in **Development mode** is enough as long as every account that authorizes holds an app
   role (admin, developer or tester). Posts made by an app in development mode are real; they are
   just limited to those roles. Going live requires App Review for `pages_manage_posts`,
   `instagram_content_publish` and `business_management`.
4. Scopes Conductor requests: `pages_show_list`, `pages_manage_posts`, `pages_read_engagement`,
   `instagram_basic`, `instagram_content_publish`, `business_management`. Grant all of them at
   consent; the Page picker after consent lists only Pages the token can see.
5. If you leave the Page picker without choosing (an error, a closed tab), the connection is stored
   but cannot publish, and Posts offer only the manual Facebook and Instagram lanes. The connection
   row on the Meta page then shows **Choose account**; that reopens the picker.

### TikTok

1. A TikTok account for the creator, and a **TikTok for Developers** app with *Login Kit* and
   *Content Posting API* (direct post) added, plus the redirect URI above.
2. Scopes Conductor requests: `user.info.basic`, `video.publish`, `video.upload`, `video.list`.
   `video.list` is what the metrics feed reads with; a connection made before it was requested has to
   be reconnected before metrics arrive.
3. **An unaudited app can only post privately.** Until TikTok's audit passes, the creator's allowed
   privacy levels come back as `SELF_ONLY` and Conductor refuses anything else at the gate. Test with
   `SELF_ONLY` first; the post appears on the creator's profile visible to them alone.
4. **Photo posts need a verified URL prefix.** TikTok fetches images by URL, so the storage host
   (the signed-URL host of `GCP_STORAGE_BUCKET_NAME`) must be registered under *URL properties* in the
   developer portal. Without it every photo post fails with a message naming this; video posts upload
   their bytes and are unaffected.

## Connect the accounts in Conductor

Only a workspace **ADMIN** can do the first two steps.

1. Integrations → *Meta* → **Platform app credentials** → enter the app id and secret → **Verify**.
   The card should say the pair is valid. Repeat for *TikTok* with its client key and secret.
2. **Authorize** → consent as the account that holds the app role → pick the Page. The connection
   card should show the Page and, if linked, the Instagram username. For TikTok the card shows the
   creator's nickname and caches the privacy levels the account may use.
3. Open a Post's *Publishing to* list. You should see one row per platform: the Page, the linked
   Instagram account, the TikTok creator, and each platform's manual destination. The format control
   (Feed / Reel / Story) shows for the Meta rows only.

## The test matrix

Use the seeded MARKETING workflow (with review) for the first pass so nothing goes out without a human
approval, or import `marketing-autopilot.workflow.json` to go straight from Draft to Scheduled.
Schedule every Post at least **15 minutes** out (Facebook's native floor is 10; the extra is slack).

Watch two places while it runs: the Post's readiness card and destination rows in the UI, and the
backend log:

```bash
export CONDUCTOR_GCP_PROJECT=ai-conductor-prod
./scripts/logs.sh --since 30m | grep -E 'Armed|Handing off|Dispatching publish|published on|rolled up|stale|failed'
```

`Armed` means a Cloud Task was created for that step; `Handing off` is the native lane giving the post
to the platform's scheduler; `Dispatching publish` is Conductor firing it at the time; `rolled up` is
the Post reaching Published or Failed.

| # | Post | Destination and format | Expect |
|---|---|---|---|
| 1 | One JPEG, caption | Facebook Page, feed | Handed off at approval (`HANDED_OFF`), appears on the Page as a scheduled post, goes live at the time, row `PUBLISHED` with permalink, Post → Published |
| 2 | Three JPEGs | Facebook Page, feed | Multi-photo post, same lifecycle as 1 |
| 3 | One vertical MP4, 3–90 s | Facebook Page, feed **or** reel | Published as a Reel (Meta only accepts Reels for Page video). Reels schedule at most 29 days out |
| 4 | One 9:16 JPEG or ≤60 s MP4 | Facebook Page, story | Row stays `PENDING` until the time, then Conductor fires it (`Dispatching publish`), story on the Page, 1-minute lead accepted |
| 5 | One JPEG, aspect 4:5–1.91:1 | Instagram, feed | `Dispatching publish` at the time, media appears, permalink on the row |
| 6 | 2–10 mixed JPEG/MP4 | Instagram, feed | Carousel, cropped to the first item's aspect (warning at preflight if they differ) |
| 7 | One vertical MP4 + `coverAssetId`, `shareToFeed`, up to 3 `collaborators`, `audioName` | Instagram, reel | Reel with the chosen cover; collaborators receive invites |
| 8 | One 9:16 JPEG | Instagram, story | Story appears; the caption is dropped (preflight warns) |
| 9 | One MP4 ≤ creator cap, `privacyLevel: SELF_ONLY`, toggles, `isAigc`, `videoCoverTimestampMs` | TikTok | Consent recorded by the creator in the UI first (the gate blocks without it), post appears privately with the AI label and chosen cover frame |
| 10 | 2–35 JPEG/WEBP, `photoCoverIndex`, `autoAddMusic` | TikTok photo post | Needs the verified URL prefix; cover from the chosen index |
| 11 | Any of 1–3 | Unschedule from Scheduled | Native post disappears from the Page's scheduled posts (`REVOKED`), Post back to Approved |
| 12 | Any failed row | **Retry** on the Post | Row back to `PENDING` with a fresh idempotency key, fires again |

Also worth one deliberate failure: a TikTok Post with `privacyLevel: PUBLIC_TO_EVERYONE` on an
unaudited app must be refused at the gate, naming the allowed levels.

## After publishing: confirmation and metrics

- Native destinations (Facebook feed and reels) are confirmed live at the fire time: a `CONFIRM` task
  asks Facebook whether the post is public, once a minute, up to 20 times. A row that never confirms
  ends `FAILED` with the platform's last answer.
- The `post_metrics` feed appears on each connection once it is active and pulls every 6 hours
  (10 calls per pull, posts up to 90 days old). To pull now:
  `POST /api/v1/projects/{projectId}/integrations/{connectorId}/feeds/{feedId}/runs`, then read
  `GET /api/v2/projects/{projectId}/work-items/{id}/publish-metrics` or the MCP `get_post_analytics`.
  Instagram counts likes and comments; Facebook adds shares; TikTok needs the `video.list` scope.

## Things that will look like bugs and are not

- **Instagram allows 100 API-published posts per 24 hours.** Conductor checks the quota before
  creating a container and refuses with a message that says to reschedule.
- **A Facebook story cannot be scheduled on Facebook's side**, so its row does not hand off; it waits
  `PENDING` and fires at the time. That is the app-managed lane, not a stuck row.
- **A vertical video under three minutes on YouTube becomes a Short.** Preflight warns.
- **Signed URLs expire after 15 minutes.** Media is fetched at hand-off or dispatch, so this only
  bites if a platform is slow to pull a large video; the row fails with the platform's message and
  Retry re-signs.

## Sign-off

The feature is proven when rows 1, 3, 4, 5, 7, 8 and 9 have published, one native post has been
unscheduled cleanly, one failed row has been retried, and a metrics pull has filled at least one
target's counts. Record the platform post ids and permalinks from the destination rows in the test
workspace's Post comments so the run can be audited later.
