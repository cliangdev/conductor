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

## Words Meta and TikTok use

| Word | What it means here |
|---|---|
| **Facebook profile** | You, as a person. Conductor never posts to a profile. |
| **Facebook Page** | A public page a business or brand runs. Conductor posts to a Page. You "switch into" a Page to manage it. |
| **Instagram professional account** | An Instagram account switched to the **Business** or **Creator** type. Only these can be posted to by an app. |
| **Linking** | Attaching the Instagram professional account to the Facebook Page. Instagram is only reachable through the Page it is linked to. |
| **Business portfolio** | Meta's container for a company's Pages and accounts (formerly "Business Manager"). Meta may create one for you during linking; you do not need to set it up yourself. |
| **Meta app** | A record in Meta's developer site with an **App ID** and **App Secret**. Conductor logs in *as this app* to post on your behalf. |
| **App roles** | People allowed to use a Meta app while it is in **Development mode**. Anyone who authorizes in Conductor must have one. |
| **TikTok app** | The same idea on TikTok's developer site, with a **Client key** and **Client secret**. |
| **Sandbox** | TikTok's way to test an app before it is reviewed: a copy of the app with its own key and secret that only named test accounts can use. |

---

## Part 2 — Set up Instagram and Facebook (30 minutes)

### 2A. Make the Instagram account a professional account

Do this on your phone in the Instagram app, logged in as the account you will post to.

5. [ ] Tap your profile picture (bottom right).
6. [ ] Tap the **☰** menu (top right) → **Settings and activity**.
7. [ ] Scroll to **For professionals** → tap **Account type and tools**.
8. [ ] Tap **Switch to professional account** → **Continue**.
9. [ ] Pick a category (anything, e.g. *Local business*) → **Done**.
10. [ ] Choose **Business** (or **Creator**; both work) → **Next**. Skip the contact details if asked.
    *You should see:* your profile now shows the category under your name, and the menu has a
    **Professional dashboard** entry.

### 2B. Have a Facebook Page

Do this on a computer at facebook.com, logged in as the person who will authorize Conductor.

11. [ ] If you already administer a Page, skip to 2C.
12. [ ] Otherwise: left menu → **Pages** → **Create new Page** → name it (e.g. *Publishing Lab*),
    pick a category → **Create Page**. Skip every optional step.
    *You should see:* the new Page open, with your name as its admin.

### 2C. Link the Instagram account to the Page

**Why this step exists.** Instagram has no way for an app to post to an account on its own. Meta only
lets an app post to an Instagram account *through a Facebook Page that the account is attached to*.
Conductor therefore never talks to Instagram directly: it talks to your Page, and the Page passes
the post to the Instagram account linked to it. With no link, Conductor cannot see the Instagram
account at all and shows no Instagram row.

**What linking does.** It records, once, in Meta's system that this Instagram account belongs with
this Page. Your Instagram followers see nothing. Posts are not copied between the two automatically.

Do this on a computer at facebook.com, logged in as the person who administers the Page.

13. [ ] Open the Page. If the top of the screen offers **Switch now**, click it, so you are acting as
    the Page rather than as yourself.
14. [ ] Click **Settings** (left menu, near the bottom of the Page's menu).
15. [ ] In the settings menu click **Linked accounts**.
16. [ ] Next to **Instagram** click **Connect account**.
17. [ ] A login box appears. Log in with the Instagram account from 2A (the one you will post to).
    If Meta says the account must be added to a business portfolio, click **Continue** and let it
    create one.
    *You should see:* the Instagram username listed under **Linked accounts**. That is the check that
    matters. Later, in Conductor, this is what makes an `@yourhandle` row appear under INSTAGRAM.
    If Meta says the account is personal, go back to 2A and switch it to a professional account.

### 2D. Register as a Meta developer, then create the Meta app

You need a **Meta developer account** before you can create an app. It is not a separate account:
it is your own Facebook login, registered once on Meta's developer site. Free, immediate, no review.

18. [ ] Go to https://developers.facebook.com and click **Log In** (top right) with the same Facebook
    login that administers the Page.
    - If you have never used the developer site, click **Get Started**, accept the developer terms,
      and confirm the email address or phone number it asks for. *You should see:* your name in the
      top right and a **My Apps** menu.
19. [ ] Top right → **My Apps** → **Create App**.
20. [ ] **App name**: `Conductor Publishing Lab`. **Contact email**: yours. → **Next**.
21. [ ] **Use cases**: tick the use case for managing a Page (named like **Manage everything on your
    Page**) and the one for Instagram content. If the screen instead offers app *types*, choose
    **Business**. → **Next**.
22. [ ] **Business**: pick the business portfolio Meta created in step 17, or **I don't want to connect
    a business portfolio yet**. → **Next**.
23. [ ] **Requirements**: read, → **Next**. **Overview** → **Go to dashboard**.
    *You should see:* the app dashboard with **App Mode: Development** at the top. Leave it in
    Development mode for this whole test.

### 2E. Tell the app where Conductor is, and what it may do

24. [ ] In the dashboard's left menu find **Facebook Login for Business** → **Settings**. If it is not
    there: **Use cases** → your Page use case → **Customize** → **Settings**.
25. [ ] In **Valid OAuth Redirect URIs** paste the redirect URI from step 4 → **Save changes**.
26. [ ] Still under the use case → **Customize** → **Permissions**: make sure these six are added
    (click **Add** next to any that are missing):
    `pages_show_list`, `pages_manage_posts`, `pages_read_engagement`, `instagram_basic`,
    `instagram_content_publish`, `business_management`.
    *Why:* these are exactly what Conductor asks for at consent. A missing one makes consent fail
    or the Instagram row disappear.

### 2F. Copy the keys and add yourself to the app

27. [ ] Left menu → **App settings** → **Basic**.
28. [ ] Copy **App ID**. Next to **App secret** click **Show**, re-enter your Facebook password, copy
    it. Keep both for Part 4.
29. [ ] Left menu → **App roles** → **Roles**. Confirm the person who will click **Authorize** in
    Conductor is listed under **Administrators**, **Developers** or **Testers**. If it is someone
    else, click **Add people**, add them as a Tester, and have them accept the invite (Facebook →
    Settings → Apps and websites).
    *Why:* in Development mode only people with a role can log in through the app. Their posts are
    still real.

That is the whole Meta side. You do **not** need App Review for this test.

---

## Part 3 — Set up TikTok (25 minutes)

### 3A. The account

30. [ ] Have a TikTok account you can log in to on this computer. Any account type works. Note its
    username; you will need it in 3E.

### 3B. Create the developer app

Do this at https://developers.tiktok.com.

31. [ ] **Log in** (top right) with a TikTok account or email. If asked, create a developer account
    and verify the email.
32. [ ] Click your profile icon (top right) → **Manage apps** → **Connect an app**.
33. [ ] If it asks for an owner, pick or create an **Organization** → **Confirm**.
34. [ ] **App details**: name `Conductor Publishing Lab`, a category, a one-line description. Under
    **Platforms** tick **Web** and enter your Conductor frontend URL as the website. Add **Terms of
    Service URL** and **Privacy Policy URL** if the form requires them (any page of yours will do
    for a sandbox test). → **Save**.
    *You should see:* the app page with **Credentials**, **Products**, **Scopes** and **Sandbox**
    in the left panel.

### 3C. Add the three products

35. [ ] **Products** → **Add products** → tick **Login Kit** → **Add**.
36. [ ] **Add products** → tick **Content Posting API** → **Add**. In its settings tick **Direct Post**.
37. [ ] **Add products** → tick **Display API** → **Add**.
    *Why:* Conductor asks for the `video.list` scope to read your videos' view counts, and that scope
    comes from Display API. Without it consent fails with an invalid-scope error.

### 3D. Redirect URI and scopes

38. [ ] **Login Kit** → **Redirect URI** (Web): paste the redirect URI from step 4 → **Save**.
39. [ ] **Scopes**: confirm all four are listed: `user.info.basic`, `video.publish`, `video.upload`,
    `video.list`. Each appears once its product is added.

### 3E. Make a sandbox and add your account to it

Until TikTok reviews an app, only a sandbox can be used to log in and post. Posts made from a
sandbox are **visible only to the account that posted them**. That is what this test expects.

40. [ ] Left panel → **Sandbox** → **Create sandbox** → name `Lab` → **Create**.
41. [ ] In the sandbox, **Target users** → **Add** → enter the username from step 30. Have that
    account accept if TikTok sends it a prompt.
42. [ ] Make sure the sandbox lists the same products and scopes as 3C and 3D (they are copied in;
    add any that are missing).

### 3F. Copy the keys

43. [ ] Inside the **sandbox**, open **Credentials**. Copy **Client key** and **Client secret** (click
    the eye icon to reveal). Keep them for Part 4.
    *Careful:* the production app has its own key and secret. Use the **sandbox** pair now; switch
    Conductor to the production pair only after TikTok's review passes.

### 3G. Only if you will test photo posts

44. [ ] In Conductor, open any uploaded image and copy the **host** of its URL (it is a Google Cloud
    Storage host, e.g. `storage.googleapis.com`).
45. [ ] In the TikTok app page → **URL properties** → **Add property** → **URL prefix** → enter
    `https://<that host>/` → follow the verification it asks for.
    *Why:* TikTok fetches photos by URL and refuses hosts it has not verified. Video posts upload
    their bytes and do not need this.

---

## Part 4 — Connect the accounts in Conductor (10 minutes)

You must be an ADMIN of the Publishing Lab workspace.

46. [ ] In Conductor open **Integrations** → **Meta**.
47. [ ] Under **Platform app credentials** click **Set a credential for this workspace**. Paste the
    App ID and App Secret from step 28 → Save. *You should see:* the badge change to **Configured**.
48. [ ] Click **Verify**. *You should see:* a green report saying the credentials are valid.
49. [ ] Click **Authorize**. Log in to Facebook as the account from step 29, grant every permission,
    pick your Page. *You should see:* a connection card with the Page name and the Instagram
    username from step 17.
50. [ ] Open **Integrations** → **TikTok**. Repeat steps 47–49 with the sandbox Client key and Client secret
    from step 43, logging in as the creator. *You should see:* a card with the creator's nickname.
51. [ ] Open **Marketing → Posts** → **New Post**. Under **Publish to** you should see: your Page
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

52. [ ] **Feed photo.** One JPEG, a caption, destination = your Page, format **Feed**.
    *Expect:* row `HANDED_OFF` right after approval; the post appears under the Page's
    **Scheduled posts**; at the time it goes live and the row turns `PUBLISHED` with a link.
53. [ ] **Several photos.** Three JPEGs, same destination and format. *Expect:* one multi-photo post,
    same lifecycle as 52.
54. [ ] **Reel.** One vertical MP4 between 3 and 90 seconds, format **Reel**. *Expect:* it publishes as
    a Reel. (A Facebook video with format Feed also becomes a Reel; Facebook no longer accepts other
    Page videos.)
55. [ ] **Story.** One 9:16 JPEG, format **Story**. *Expect:* the readiness card warns that the caption
    will be dropped; the row stays `PENDING` until the time, then turns `PUBLISHED`; the story is on
    the Page. A story can be scheduled as little as 1 minute out.
56. [ ] **Unschedule.** Create one more feed photo Post, get it to Scheduled, then change its status
    back to **Approved**. *Expect:* the row turns `REVOKED` and the post disappears from the Page's
    scheduled posts.

---

## Part 7 — Instagram tests (about 30 minutes of waiting)

57. [ ] **Feed image.** One JPEG with aspect between 4:5 and 1.91:1 (a square is fine), destination =
    `@yourhandle`, format **Feed**. *Expect:* `PENDING` until the time, then `PUBLISHED` with a link.
58. [ ] **Carousel.** Two to ten files, JPEG and MP4 mixed. *Expect:* one carousel; if the aspects
    differ the readiness card warns they will be cropped to the first one.
59. [ ] **Reel with options.** One vertical MP4, format **Reel**. Click **Customize for this
    destination** and set: cover image (one of the Post's images), **Share to feed** on, up to three
    collaborator usernames, an audio name. *Expect:* the Reel uses your cover; collaborators get an
    invite.
60. [ ] **Story.** One 9:16 JPEG, format **Story**. *Expect:* caption-dropped warning, `PENDING` then
    `PUBLISHED`; the story is on the account.

> Instagram allows 100 API posts per account per 24 hours. If you hit it, the readiness card says so
> and asks you to reschedule.

---

## Part 8 — TikTok tests (about 20 minutes of waiting)

61. [ ] **Video.** One MP4 shorter than the creator's cap (the card shows it), destination = the
    creator. Under the TikTok options set **Who can view** = `SELF_ONLY`, leave duet/stitch/comments
    as you like, turn **AI-generated** on, set a cover frame time. *Expect:* the readiness card asks
    for the creator's **consent**; click through the consent step as the creator. After approval:
    `PENDING`, then at the time `PUBLISHED`; the video is on the profile, private, with the AI label
    and your cover frame.
62. [ ] **Refusal check.** Make a second video Post and set **Who can view** = `PUBLIC_TO_EVERYONE`.
    *Expect:* the readiness card blocks it and names the levels the account may use. Delete or fix it.
63. [ ] **Photo post** (only if you did step 45). Two or more JPEGs, pick the cover index and
    **Auto add music**. *Expect:* a photo post on the profile with your cover. Without step 45 the row
    fails with a message about the verified URL prefix; that is the expected message.

---

## Part 9 — Retry and metrics (10 minutes)

64. [ ] **Retry.** Take any `FAILED` row (step 63 without step 45 is a convenient one, or unplug a
    permission on purpose), fix the cause, click **Retry** on the Post. *Expect:* the row goes back to
    `PENDING` and fires again.
65. [ ] **Metrics.** On each connection card there is a **post_metrics** feed that pulls every 6 hours.
    Run it now with:

    ```bash
    curl -X POST -H "Authorization: Bearer <your API key>" \
      https://conductor-backend-199707291514.us-central1.run.app/api/v1/projects/<projectId>/integrations/meta/feeds/<feedId>/runs
    ```

    (The feed id is on the connection's Feeds panel.) *Expect:* the published Posts show views, likes
    and comments under **What happened afterwards**, or via the MCP tool `get_post_analytics`. TikTok
    metrics need the `video.list` scope from step 39; if the connection was made before that scope
    existed, Authorize again.

---

## Part 10 — Sign off

The feature is proven when all of these are ticked:

- [ ] 52 Facebook feed photo published and went live at the time
- [ ] 54 Facebook Reel published
- [ ] 55 Facebook story published
- [ ] 56 Unschedule removed the scheduled post from Facebook
- [ ] 57 Instagram feed image published
- [ ] 59 Instagram Reel published with cover and options
- [ ] 60 Instagram story published
- [ ] 61 TikTok video published privately with the AI label
- [ ] 64 A failed row was retried successfully
- [ ] 65 A metrics pull filled at least one Post's counts

Paste each published row's link into a comment on its Post so the run can be audited later.

---

## If something goes wrong

| You see | Do |
|---|---|
| **Authorize** is greyed out | Step 47 was skipped or the credential was cleared. Only an ADMIN can set it. |
| Consent page says the redirect URI is invalid | Step 25 or 38: the URI must match step 4 exactly. |
| Instagram row missing after connecting Meta | Step 17: the Instagram account is not linked to the Page, or `instagram_basic` was not granted. |
| TikTok row blocks on privacy level | Expected on an unaudited app. Use `SELF_ONLY`. |
| TikTok photo post fails naming a "verified URL prefix" | Step 45. |
| Readiness card says the time is too soon | Facebook needs 10 minutes' notice for feed posts and reels. Move it out. |
| Row `FAILED` with a platform message about the media URL | Retry. The storage link expired before the platform fetched it. |
