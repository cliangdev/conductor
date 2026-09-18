# TikTok app review: resubmission pack

TikTok rejected the first submission for three reasons. Two were about URLs. The third, "does not
support personal or internal company use", was about how the app described itself. This doc holds
the field values, the copy and the scope justification for the resubmission, so the next reviewer
sees a multi-tenant product instead of an internal posting utility.

What the reviewer wrote:

> TikTok for Developers currently does not support personal or internal company use. Not acceptable:
> Display posts from the TikTok account(s) you or your team manage on your website. Not acceptable: A
> utility tool to help upload contents to the account(s) you or your team manages. The app name does
> not match the website title. Please ensure the website's title (the name displayed on the browser
> tab and homepage) matches the app name exactly.

## What changed in the product

| Before | Now |
|---|---|
| `/` redirected to `/login`, so no page was reachable signed out | `/` is a public, statically rendered landing page |
| No privacy policy or terms anywhere | Public `/privacy` and `/terms`, linked from every page footer |
| Browser tab title varied by route | The homepage tab title is exactly `Conductor` |
| Frontend served from its Cloud Run URL | Served from `https://conductor.rexipe.io` |

Signup is self-serve and needs to stay that way. Anyone can sign in with a Google account and gets a
workspace. **Do not put it behind a waitlist before review.** A reviewer who cannot reach the
"Connect TikTok" screen has no way to approve the app.

## Field values

Set these exactly in the TikTok developer portal.

| Field | Value |
|---|---|
| App name | `Conductor` |
| Website URL | `https://conductor.rexipe.io` |
| Terms of Service URL | `https://conductor.rexipe.io/terms` |
| Privacy Policy URL | `https://conductor.rexipe.io/privacy` |
| Redirect domain | `rexipe.io`, since the OAuth callback lives on the API host |
| Redirect URI | `https://<api-host>/api/v1/oauth/callback`, see `OAuthFlowService#callbackUrl` |
| Scopes | `user.info.basic`, `video.publish`, `video.upload`, `video.list` |

The app name, the homepage `<h1>` and the browser tab title all have to read `Conductor`. The
reviewer checks all three. `src/app/page.tsx` sets `title: 'Conductor'` explicitly so the root page
escapes the layout's `%s · Conductor` template.

## Use-case description

Replace the rejected text with this.

> Conductor is a coordination platform that software and marketing teams use to review and approve
> work produced by AI agents. Any team can sign up, create a workspace and invite colleagues.
>
> Inside a workspace, an agent drafts a social post, a human teammate reviews and approves it, and
> on approval Conductor publishes it to the TikTok account **that workspace's own administrator
> connected** through TikTok's sign-in screen. Conductor then reads back the public performance
> counters of the videos it published, so that workspace can see which of its own content performed
> best and the next draft is informed by real results.
>
> Conductor's customers are the teams that sign up for it. Each workspace connects and manages only
> its own TikTok account, sees only its own data, and can disconnect at any time. Conductor does not
> operate or manage those accounts, and it does not display any customer's posts or metrics
> publicly.

Why this clears the objection: the rejected phrasings ("*your* website", "the account(s) *you or
your team* manage") describe a first-party tool. The product is multi-tenant. The account belongs to
the customer who signed up, not to us.

## Justifying `video.list`

`video.list` is requested, and it is worth being precise about what it does, because "read the
user's videos" is exactly the shape TikTok rejects when it feeds a public display.

What the code does. `TikTokConnector#queryVideoMetrics`
(`conductor-backend/.../connector/tiktok/TikTokConnector.java`) is called only by
`PostMetricsFeedPuller` (`conductor-backend/.../service/publish/PostMetricsFeedPuller.java`), which:

1. reads `post_publish_target` rows, the destinations Conductor itself published to, for one
   connection, and passes only those platform post IDs as `post_ids`;
2. is bounded by an `IngestQuotaSpec` (`maxCallsPerPull`, `maxPostAgeDays`) and reads newest first,
   so it never walks the account's library;
3. writes one `post_publish_target_metric` reading per destination per UTC hour, idempotently;
4. surfaces the result only inside that workspace, on its Marketing then Insights page and in a
   weekly Knowledge page. (The Insights page ships on `feat/marketing-insights-v1`. It has to be
   deployed before the demo video is recorded, because step 6 below shows it.)

So the read is scoped to content the user published through Conductor, and the audience is the
workspace that published it. There is no browse, no feed, no public embed and no third-party
sharing. Point the reviewer at the Insights page in the demo. The feature is the justification.

Suggested portal wording:

> `video.list` is used only to read back the public view, like, comment and share counts of the
> videos Conductor itself published for the workspace. Conductor passes the specific video IDs it
> recorded at publish time. It never lists or browses the account's other videos. The counts are
> shown to that workspace's own members on its Insights page and summarised in a weekly internal
> report. They are not displayed publicly, embedded on any website, or shared with third parties.

If the reviewer still objects, the fallback is to resubmit with `user.info.basic`, `video.publish`
and `video.upload` only, ship publishing, then request `video.list` once the app is approved.

## Demo video script

Record signed out, in one take, roughly two to three minutes, no cuts. The reviewer needs to see
that a stranger can sign up and connect their own account.

1. Open `https://conductor.rexipe.io` signed out. Show that the tab title reads `Conductor`. Scroll
   the landing page. Click the footer's **Privacy Policy**, then **Terms of Service**, showing that
   both load without a login.
2. Click **Create your workspace**, sign in with a Google account, and land in a fresh workspace.
   Say out loud that any team can do this and the workspace is theirs.
3. Go to **Settings**, then **Integrations**, pick TikTok, and complete TikTok's own sign-in screen.
   Show the consent screen and the permissions granted.
4. Open a Post Work Item, show the draft, add a reviewer, and **approve** it. Say that nothing
   publishes until a person approves it.
5. Show the Post going live and the resulting video on the TikTok account.
6. Open **Marketing**, then **Insights**, and show the read-back counters and the weekly report.
   This is `video.list` in use, inside the workspace only. Needs
   `feat/marketing-insights-v1` deployed.
7. Go back to **Settings**, then **Integrations**, and show **Disconnect** on the connection.

## Cutover checklist for conductor.rexipe.io

How the existing subdomains are actually wired, confirmed 2026-09-18 against the live projects:
`rexipe.io` DNS is hosted at GoDaddy (`ns07/ns08.domaincontrol.com`), and each subdomain is a Cloud
Run domain mapping plus a `CNAME` to `ghs.googlehosted.com`. `admin-staging.rexipe.io` maps to
`nexus-dashboard` and `api-staging.rexipe.io` maps to `nexus-backend`, both in `archon-staging`,
`us-central1`. Conductor lives in a different project, `ai-conductor-prod` (project number
199707291514), with `conductor-frontend` and `conductor-backend` also in `us-central1`.

### Who has to run what

Creating a Cloud Run domain mapping requires the caller to be a **verified owner of the domain in
Google Search Console**, which is separate from project IAM. Both existing mappings were created by
`caluvdsnuts@gmail.com`, the project owner. `bryan.sheddy@gmail.com` holds `roles/editor`: enough to
update Cloud Run services, not enough to create a mapping (`gcloud` answers "You currently have no
verified domains") and not enough to read or write secrets.

So steps 1 and 4 below need the owner account, or the owner first has to add
`bryan.sheddy@gmail.com` as a verified owner of `rexipe.io` in Search Console.

### Steps

1. **Create the mapping**, as `caluvdsnuts@gmail.com`:
   ```bash
   gcloud beta run domain-mappings create \
     --service=conductor-frontend \
     --domain=conductor.rexipe.io \
     --region=us-central1 \
     --project=ai-conductor-prod
   ```
2. **Add the DNS record at GoDaddy**: type `CNAME`, name `conductor`, value `ghs.googlehosted.com.`
   This is the same record shape the other two subdomains use. Then wait for the certificate:
   ```bash
   gcloud beta run domain-mappings describe --domain=conductor.rexipe.io \
     --region=us-central1 --project=ai-conductor-prod
   ```
3. **Allow both origins while DNS propagates.** CORS is built from `frontend.url` plus
   `FRONTEND_CORS_ADDITIONAL_ORIGINS`, comma-separated:
   ```bash
   gcloud run services update conductor-backend --region=us-central1 --project=ai-conductor-prod \
     --update-env-vars="FRONTEND_CORS_ADDITIONAL_ORIGINS=https://conductor-frontend-199707291514.us-central1.run.app,https://conductor-frontend-x6setx6tpa-uc.a.run.app"
   ```
4. **Flip the canonical host.** `FRONTEND_URL` on the backend is a **Secret Manager secret**, not a
   plain env var, so it takes a new secret version rather than an env update, and it needs the owner
   account:
   ```bash
   printf 'https://conductor.rexipe.io' | gcloud secrets versions add FRONTEND_URL \
     --data-file=- --project=ai-conductor-prod
   ```
   The service reads `latest`, so it picks the new value up on its next revision. Redeploy the
   backend to make that immediate.
5. **Frontend build arg.** `NEXT_PUBLIC_SITE_URL` is baked in at image build time, and it was
   missing from the repo variables entirely until 2026-09-18. It is now set to
   `https://conductor.rexipe.io`. Any build started before it was set has an empty value, so
   re-run Frontend CD after changing it rather than expecting a restart to pick it up.
6. **Firebase**: add `conductor.rexipe.io` under Authentication, Settings, Authorized domains, and
   to the Google OAuth client's authorized JavaScript origins. Console only.
7. **Update the platform consoles.** TikTok (the fields above), Meta and Google all point at the new
   URLs.
8. **Verify signed out.** `/`, `/privacy` and `/terms` all return 200, and the homepage title is
   exactly `Conductor`. Then sign in and publish once, end to end.
9. **Clean up.** Once the new host is serving, clear `FRONTEND_CORS_ADDITIONAL_ORIGINS`.

## Open items before submitting

- Confirm the operating legal entity's name and the governing-law jurisdiction in `/terms`. Section
  14 is written without naming a jurisdiction, as a placeholder.
- ~~Confirm the contact mailbox.~~ Confirmed on 2026-09-18: `support@rexipe.io`, which is already
  what `CONTACT_EMAIL` renders in `conductor-frontend/src/components/site/SiteChrome.tsx`. Keep it
  monitored through review, because the reviewer may write to it.
