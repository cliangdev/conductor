# Testing publishing against real Facebook, Instagram and TikTok accounts

A step-by-step checklist. Do the parts in order; each step says what to do and what you should see.
Tick them off as you go. How the pipeline works is in [`publishing.md`](publishing.md); you do not
need it to follow this.

**You need:** a production Conductor login with the ADMIN role in a workspace, a Facebook account
that administers a test Page, a TikTok account, and about two hours.

---

## Part 1 — Make a test workspace (5 minutes)

1. [ ] Sign in to Conductor (production).
2. [ ] Create a new workspace named **Publishing Lab**. Use this workspace for everything below.
   *Why: every automated destination publishes for real. Keep it away from real content.*
3. [ ] Open **Workflows**. Confirm a **Post** workflow exists (the seeded MARKETING one). Leave it as is.
4. [ ] Write down the backend URL. It is `https://conductor-backend-199707291514.us-central1.run.app`.
   Your OAuth redirect URI is that URL plus `/api/v1/oauth/callback`:

   ```
   https://conductor-backend-199707291514.us-central1.run.app/api/v1/oauth/callback
   ```

   You will paste this into both platform apps.

> Do not run this on a laptop or a PR preview deploy. Facebook and TikTok fetch media from Conductor's
> storage URLs and redirect OAuth back to the backend, so only production works, and a preview deploy
> writes into the production database.

---

## Part 2 — Set up Facebook and Instagram (20 minutes)

### The accounts

5. [ ] Have a Facebook **Page** you administer. Create one if needed (Facebook → Pages → Create).
6. [ ] Have an Instagram account switched to **Business** or **Creator** (Instagram app → Settings →
   Account type).
7. [ ] Link that Instagram account to the Page: Page → Settings → **Linked accounts** → Instagram →
   Connect. *You should see:* the Instagram username under the Page's linked accounts.

### The Meta app

8. [ ] Go to https://developers.facebook.com → **My Apps** → **Create App**. Type: **Business**. Name it
   `Conductor Publishing Lab`.
9. [ ] In the app, **Add product** → **Facebook Login for Business** → Set up.
10. [ ] **Add product** → **Instagram** → Set up.
11. [ ] Facebook Login for Business → **Settings** → **Valid OAuth Redirect URIs** → paste the redirect
    URI from step 4 → Save.
12. [ ] **App settings → Basic**: copy the **App ID** and the **App Secret** (click Show). Keep them for
    Part 4.
13. [ ] **App roles → Roles**: make sure the Facebook account you will authorize with is listed as
    Admin, Developer or Tester. *Why: the app stays in Development mode for this test, and only
    accounts with a role can use it. That is fine; posts are still real.*

Conductor will ask for these permissions at consent; grant all of them:
`pages_show_list`, `pages_manage_posts`, `pages_read_engagement`, `instagram_basic`,
`instagram_content_publish`, `business_management`.

---

## Part 3 — Set up TikTok (20 minutes)

14. [ ] Go to https://developers.tiktok.com → **Manage apps** → **Connect an app**. Name it
    `Conductor Publishing Lab`.
15. [ ] **Add products**: **Login Kit** and **Content Posting API**. In Content Posting API, enable
    **Direct Post**.
16. [ ] Login Kit → **Redirect URI**: paste the redirect URI from step 4 → Save.
17. [ ] **Scopes**: make sure `user.info.basic`, `video.publish`, `video.upload` and `video.list` are
    enabled for the app.
18. [ ] Copy the **Client key** and **Client secret**. Keep them for Part 4.
19. [ ] Optional, only for **photo posts**: under **URL properties**, add and verify the storage host.
    Find it by opening any uploaded image in Conductor and copying the host of its URL (it is a Google
    Cloud Storage host). *Skip this if you only test video posts.*

> Until TikTok audits the app, it can only post **privately** (`SELF_ONLY`). That is expected. Every
> TikTok test below uses `SELF_ONLY`; the post shows on the creator's profile, visible only to them.

---

## Part 4 — Connect the accounts in Conductor (10 minutes)

You must be an ADMIN of the Publishing Lab workspace.

20. [ ] In Conductor open **Integrations** → **Meta**.
21. [ ] Under **Platform app credentials** click **Set a credential for this workspace**. Paste the
    App ID and App Secret from step 12 → Save. *You should see:* the badge change to **Configured**.
22. [ ] Click **Verify**. *You should see:* a green report saying the credentials are valid.
23. [ ] Click **Authorize**. Log in to Facebook as the account from step 13, grant every permission,
    pick your Page. *You should see:* a connection card with the Page name and the Instagram
    username from step 7.
24. [ ] Open **Integrations** → **TikTok**. Repeat steps 21–23 with the Client key and Client secret
    from step 18, logging in as the creator. *You should see:* a card with the creator's nickname.
25. [ ] Open **Marketing → Posts** → **New Post**. Under **Publish to** you should see: your Page
    under FACEBOOK, `@yourhandle` under INSTAGRAM, the creator under TIKTOK, plus a "(manual)" row
    under each platform. Cancel the modal.

If a row is missing, the connection card for that platform will say why (usually a permission not
granted at consent; click **Authorize** again and grant it).

---

## Part 5 — How every test below works

Each test is the same loop:

- **New Post** → caption → **Choose files** → tick the destination → pick the format if offered →
  set **When** to at least **15 minutes** from now → **Create post**.
- On the Post page, the **readiness card** must say it is ready. If not, it lists exactly what to fix.
- Click **Submit for review**, approve it as a reviewer, and it moves to **Scheduled** by itself.
- Under **Publishing to**, each destination row shows a state. Wait for the time; refresh.

What the states mean:

| State | Meaning |
|---|---|
| `PENDING` | Waiting for the time. Normal for Instagram, TikTok and Facebook stories. |
| `HANDED_OFF` | Facebook has it in its own scheduled posts. Normal for Facebook feed posts and reels. |
| `PUBLISHED` | Live. The row shows the platform's link. |
| `FAILED` | The platform refused. The row shows its message. **Retry** on the Post fires it again. |

The Post itself turns **Published** when every row is published.

To watch it from the logs (optional):

```bash
export CONDUCTOR_GCP_PROJECT=ai-conductor-prod
./scripts/logs.sh --since 30m | grep -E 'Armed|Handing off|Dispatching publish|published on|rolled up|failed'
```

---

## Part 6 — Facebook tests (about 40 minutes of waiting)

26. [ ] **Feed photo.** One JPEG, a caption, destination = your Page, format **Feed**.
    *Expect:* row `HANDED_OFF` right after approval; the post appears under the Page's
    **Scheduled posts**; at the time it goes live and the row turns `PUBLISHED` with a link.
27. [ ] **Several photos.** Three JPEGs, same destination and format. *Expect:* one multi-photo post,
    same lifecycle as 26.
28. [ ] **Reel.** One vertical MP4 between 3 and 90 seconds, format **Reel**. *Expect:* it publishes as
    a Reel. (A Facebook video with format Feed also becomes a Reel; Facebook no longer accepts other
    Page videos.)
29. [ ] **Story.** One 9:16 JPEG, format **Story**. *Expect:* the readiness card warns that the caption
    will be dropped; the row stays `PENDING` until the time, then turns `PUBLISHED`; the story is on
    the Page. A story can be scheduled as little as 1 minute out.
30. [ ] **Unschedule.** Create one more feed photo Post, get it to Scheduled, then change its status
    back to **Approved**. *Expect:* the row turns `REVOKED` and the post disappears from the Page's
    scheduled posts.

---

## Part 7 — Instagram tests (about 30 minutes of waiting)

31. [ ] **Feed image.** One JPEG with aspect between 4:5 and 1.91:1 (a square is fine), destination =
    `@yourhandle`, format **Feed**. *Expect:* `PENDING` until the time, then `PUBLISHED` with a link.
32. [ ] **Carousel.** Two to ten files, JPEG and MP4 mixed. *Expect:* one carousel; if the aspects
    differ the readiness card warns they will be cropped to the first one.
33. [ ] **Reel with options.** One vertical MP4, format **Reel**. Click **Customize for this
    destination** and set: cover image (one of the Post's images), **Share to feed** on, up to three
    collaborator usernames, an audio name. *Expect:* the Reel uses your cover; collaborators get an
    invite.
34. [ ] **Story.** One 9:16 JPEG, format **Story**. *Expect:* caption-dropped warning, `PENDING` then
    `PUBLISHED`; the story is on the account.

> Instagram allows 100 API posts per account per 24 hours. If you hit it, the readiness card says so
> and asks you to reschedule.

---

## Part 8 — TikTok tests (about 20 minutes of waiting)

35. [ ] **Video.** One MP4 shorter than the creator's cap (the card shows it), destination = the
    creator. Under the TikTok options set **Who can view** = `SELF_ONLY`, leave duet/stitch/comments
    as you like, turn **AI-generated** on, set a cover frame time. *Expect:* the readiness card asks
    for the creator's **consent**; click through the consent step as the creator. After approval:
    `PENDING`, then at the time `PUBLISHED`; the video is on the profile, private, with the AI label
    and your cover frame.
36. [ ] **Refusal check.** Make a second video Post and set **Who can view** = `PUBLIC_TO_EVERYONE`.
    *Expect:* the readiness card blocks it and names the levels the account may use. Delete or fix it.
37. [ ] **Photo post** (only if you did step 19). Two or more JPEGs, pick the cover index and
    **Auto add music**. *Expect:* a photo post on the profile with your cover. Without step 19 the row
    fails with a message about the verified URL prefix; that is the expected message.

---

## Part 9 — Retry and metrics (10 minutes)

38. [ ] **Retry.** Take any `FAILED` row (step 37 without step 19 is a convenient one, or unplug a
    permission on purpose), fix the cause, click **Retry** on the Post. *Expect:* the row goes back to
    `PENDING` and fires again.
39. [ ] **Metrics.** On each connection card there is a **post_metrics** feed that pulls every 6 hours.
    Run it now with:

    ```bash
    curl -X POST -H "Authorization: Bearer <your API key>" \
      https://conductor-backend-199707291514.us-central1.run.app/api/v1/projects/<projectId>/integrations/meta/feeds/<feedId>/runs
    ```

    (The feed id is on the connection's Feeds panel.) *Expect:* the published Posts show views, likes
    and comments under **What happened afterwards**, or via the MCP tool `get_post_analytics`. TikTok
    metrics need the `video.list` scope from step 17; if the connection was made before that scope
    existed, Authorize again.

---

## Part 10 — Sign off

The feature is proven when all of these are ticked:

- [ ] 26 Facebook feed photo published and went live at the time
- [ ] 28 Facebook Reel published
- [ ] 29 Facebook story published
- [ ] 30 Unschedule removed the scheduled post from Facebook
- [ ] 31 Instagram feed image published
- [ ] 33 Instagram Reel published with cover and options
- [ ] 34 Instagram story published
- [ ] 35 TikTok video published privately with the AI label
- [ ] 38 A failed row was retried successfully
- [ ] 39 A metrics pull filled at least one Post's counts

Paste each published row's link into a comment on its Post so the run can be audited later.

---

## If something goes wrong

| You see | Do |
|---|---|
| **Authorize** is greyed out | Step 21 was skipped or the credential was cleared. Only an ADMIN can set it. |
| Consent page says the redirect URI is invalid | Step 11 or 16: the URI must match step 4 exactly. |
| Instagram row missing after connecting Meta | Step 7: the Instagram account is not linked to the Page, or `instagram_basic` was not granted. |
| TikTok row blocks on privacy level | Expected on an unaudited app. Use `SELF_ONLY`. |
| TikTok photo post fails naming a "verified URL prefix" | Step 19. |
| Readiness card says the time is too soon | Facebook needs 10 minutes' notice for feed posts and reels. Move it out. |
| Row `FAILED` with a platform message about the media URL | Retry. The storage link expired before the platform fetched it. |
