# TikTok app review — resubmission pack

TikTok rejected the first submission on three grounds. Two were about URLs; the third — "does not
support personal or internal company use" — was about how the app *described itself*. This doc holds
the field values, the copy, and the scope justification for the resubmission, so the next reviewer
sees a multi-tenant product rather than an internal posting utility.

Reviewer verbatim:

> TikTok for Developers currently does not support personal or internal company use. Not acceptable:
> Display posts from the TikTok account(s) you or your team manage on your website. Not acceptable: A
> utility tool to help upload contents to the account(s) you or your team manages. The app name does
> not match the website title. Please ensure the website's title (the name displayed on the browser
> tab and homepage) matches the app name exactly.

## What changed in the product

| Before | Now |
|---|---|
| `/` redirected to `/login`; no page was reachable signed out | `/` is a public, statically rendered landing page |
| No privacy policy or terms anywhere | Public `/privacy` and `/terms`, linked from every page footer |
| Browser tab title varied by route | Homepage tab title is exactly `Conductor` |
| Frontend served from its Cloud Run URL | Served from `https://conductor.rexipe.io` |

Signup is self-serve and stays that way: anyone can sign in with a Google account and gets a
workspace. **Do not gate it behind a waitlist before review** — a reviewer who cannot reach the
"Connect TikTok" screen has no way to approve the app.

## Field values

Set these exactly in the TikTok developer portal.

| Field | Value |
|---|---|
| App name | `Conductor` |
| Website URL | `https://conductor.rexipe.io` |
| Terms of Service URL | `https://conductor.rexipe.io/terms` |
| Privacy Policy URL | `https://conductor.rexipe.io/privacy` |
| Redirect domain | `rexipe.io` (the OAuth callback lives on the API host) |
| Redirect URI | `https://<api-host>/api/v1/oauth/callback` — see `OAuthFlowService#callbackUrl` |
| Scopes | `user.info.basic`, `video.publish`, `video.upload`, `video.list` |

The app name, the homepage `<h1>`, and the browser tab title must all read `Conductor` — the
reviewer checks all three. `src/app/page.tsx` sets `title: 'Conductor'` explicitly so the root page
escapes the layout's `%s · Conductor` template.

## Use-case description (replace the rejected text with this)

> Conductor is a coordination platform that software and marketing teams use to review and approve
> work produced by AI agents. Any team can sign up, create a workspace, and invite colleagues.
>
> Within a workspace, an agent drafts a social post; a human teammate reviews and approves it; and
> on approval Conductor publishes it to the TikTok account **that workspace's own administrator
> connected** through TikTok's OAuth screen. Conductor then reads back the public performance
> counters of the videos it published so that workspace can see which of its own content performed
> best, and so the next draft is informed by real results.
>
> Conductor's customers are the teams that sign up for it. Each workspace connects and manages only
> its own TikTok account, sees only its own data, and can disconnect at any time. Conductor does not
> operate or manage those accounts, and does not display any customer's posts or metrics publicly.

Why this clears the objection: the rejected phrasings ("*your* website", "the account(s) *you or
your team* manage") describe a first-party tool. The product is multi-tenant — the account belongs to
the signed-up customer, not to us.

## Justifying `video.list`

`video.list` is requested, and it is worth being precise about what it does and does not do, because
"read the user's videos" is exactly the shape TikTok rejects when it serves a public display.

**What the code actually does** — `TikTokConnector#queryVideoMetrics` (`conductor-backend/.../connector/tiktok/TikTokConnector.java`)
is called only by `PostMetricsFeedPuller` (`conductor-backend/.../service/publish/PostMetricsFeedPuller.java`),
which:

1. reads `post_publish_target` rows — the destinations Conductor itself published to — for one
   connection, and passes **only those** platform post IDs as `post_ids`;
2. is bounded by an `IngestQuotaSpec` (`maxCallsPerPull`, `maxPostAgeDays`), newest-first, so it
   never walks the account's library;
3. writes one `post_publish_target_metric` snapshot per destination per UTC hour, idempotently;
4. surfaces the result only inside that workspace — on its Marketing → Insights page and in a
   weekly Knowledge page. (The Insights page ships on `feat/marketing-insights-v1`; it must be
   deployed before the demo video is recorded, because step 6 below shows it.)

So the read is **scoped to content the user published through Conductor**, and the audience is **the
workspace that published it**. There is no browse, no feed, no public embed, and no third-party
sharing. Point the reviewer at the Insights page in the demo — the feature is the justification.

Suggested portal wording:

> `video.list` is used only to read back the public view, like, comment and share counts of the
> videos Conductor itself published for the workspace. Conductor passes the specific video IDs it
> recorded at publish time; it never lists or browses the account's other videos. The counts are
> shown to that workspace's own members on its Insights page and summarised in a weekly internal
> report. They are not displayed publicly, embedded on any website, or shared with third parties.

If the reviewer still objects, the fallback is to resubmit with `user.info.basic` + `video.publish`
+ `video.upload` only, ship publishing, and request `video.list` as a follow-up once the app is
approved.

## Demo video script

Record signed out, in one take, ~2–3 minutes, no cuts. The reviewer needs to see that a stranger can
sign up and connect their own account.

1. Open `https://conductor.rexipe.io` signed out. Show the tab title reads `Conductor`. Scroll the
   landing page. Click the footer's **Privacy Policy**, then **Terms of Service**, showing both load
   without a login.
2. Click **Create your workspace** → sign in with a Google account → land in a fresh workspace.
   Narrate: "any team can do this; the workspace is theirs."
3. Go to **Settings → Integrations**, pick TikTok, and complete TikTok's own OAuth screen. Show the
   consent screen and the granted scopes.
4. Open a Post Work Item, show the draft, add a reviewer, and **approve** it. Narrate that nothing
   publishes without a human approval.
5. Show the Post going live and the resulting video on the TikTok account.
6. Open **Marketing → Insights** and show the read-back counters and the weekly report — this is
   `video.list` in use, inside the workspace only. Requires `feat/marketing-insights-v1` deployed.
7. Return to **Settings → Integrations** and show **Disconnect** on the connection.

## Cutover checklist for `conductor.rexipe.io`

Run in this order; steps 1–3 are safe before any traffic moves.

1. **Cloud Run domain mapping** for the frontend service:
   ```bash
   gcloud beta run domain-mappings create \
     --service=<frontend-service> \
     --domain=conductor.rexipe.io \
     --region=<region> \
     --project=<project>
   ```
   Add the `CNAME` it prints at the `rexipe.io` DNS provider, then wait for the certificate to go
   ready (`gcloud beta run domain-mappings describe --domain=conductor.rexipe.io --region=<region>`).
2. **Allow both origins during the move.** CORS is built from `frontend.url` plus
   `FRONTEND_CORS_ADDITIONAL_ORIGINS` (comma-separated). Set the backend's
   `FRONTEND_CORS_ADDITIONAL_ORIGINS` to the current frontend Cloud Run URL, so the old hostname
   keeps working while DNS propagates.
3. **Firebase**: add `conductor.rexipe.io` under Authentication → Settings → Authorized domains,
   and to the Google OAuth client's authorized JavaScript origins.
4. **Flip the canonical host**: set the repo variable `NEXT_PUBLIC_SITE_URL=https://conductor.rexipe.io`
   (now a frontend build arg — it needs a redeploy, not just a restart) and the backend's
   `FRONTEND_URL=https://conductor.rexipe.io`. Redeploy both.
5. **Update the platform consoles**: TikTok (fields above), Meta, and Google to the new URLs.
6. **Verify signed out**: `/`, `/privacy`, `/terms` all return 200 and the homepage title is exactly
   `Conductor`; then sign in and publish once end to end.
7. **Clean up**: once the new host is serving, clear `FRONTEND_CORS_ADDITIONAL_ORIGINS`.

## Open items before submitting

- Confirm the operating legal entity's name and the governing-law jurisdiction in `/terms`
  (§14 is written jurisdiction-neutral as a placeholder).
- Confirm `CONTACT_EMAIL` in `conductor-frontend/src/components/site/SiteChrome.tsx` is a mailbox
  someone reads — the reviewer may write to it.
