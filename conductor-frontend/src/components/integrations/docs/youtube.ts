const doc = `## What it does

Uploads video to a **YouTube channel**, optionally scheduled — Conductor uploads the video as private with a publish time, and YouTube flips it public at that moment. A workspace can connect several channels, one connection each.

## Before you connect

YouTube publishes through Conductor's own Google app, so there is no Google Cloud project to create, no API to enable, and no redirect URI to enter. You need a **Google account that owns or manages a YouTube channel**. Click **Authorize** and sign in with that account when Google asks.

> **Until Conductor's app passes Google's YouTube API Services audit, every upload is locked to private**, server-side, regardless of the privacy status the request asks for. There is no error and no appeal: the upload succeeds, the schedule is accepted, and the video simply never goes public. Google gives no timeline for the audit. Until it passes, treat YouTube as a way to stage private videos, and confirm with a real scheduled test video before relying on it.

While Conductor's app is in Google's **Testing** status, only Google accounts on its test-user list can connect, and their authorization **expires after 7 days**, so those users reconnect weekly. A connection that has lapsed shows as needing attention in Integrations.

## Account requirements

The authorizing Google account must actually own or manage a channel. A brand-new account with no channel returns an empty channel list, which looks like a broken integration but isn't.

**One channel per connection.** If the Google account manages several channels (brand accounts), Google's account picker asks which one to authorize, and that channel is the one the connection posts to. To post to another channel, connect again and pick it.

## How authentication works

Standard Google OAuth, with four scopes:

- \`youtube.upload\` publishes videos to the channel.
- \`youtube.readonly\` reads the channel's identity (id and title) and reads a video back after it is published.
- \`yt-analytics.readonly\` reads watch time and average view percentage for performance reporting.
- \`youtube.force-ssl\` sets a video back to private when its Post is unscheduled, so a cancelled video never goes public, and adds a video to the playlists chosen on the Post. Google's sign-in screen describes it as permission to see, edit and delete your videos; Conductor only changes the privacy and schedule of videos it uploaded, and adds them to playlists.

A channel connected before \`youtube.force-ssl\` was added needs reconnecting for Unschedule and playlists to work.

Conductor resolves the channel via \`channels.list\` and stores its id and title. The app is dedicated to YouTube, separate from the Google client Search Console and GCP Billing use, so its verification doesn't affect them.

Disconnecting also removes Conductor's access in your Google Account, unless the same channel is still connected in another workspace.

## Limits and behaviour

- **Quota changed substantially and recently.** A video upload used to cost ~1600 units against a shared 10,000/day pool, giving roughly six uploads a day. Since December 2025 uploads draw on their own dedicated bucket of about **100 per day**, shared by every workspace on this deployment because they all use Conductor's one app. Check Google's live quota page rather than trusting any fixed number, including this one — Google has described the change as an ongoing transition. Note also that upload quota no longer shows up in general quota monitoring.
- **Scheduling**: a video must be uploaded as private for a publish time to apply, which is what Conductor does. A time in the past publishes immediately. Google documents no maximum how-far-ahead limit.
- **Shorts**: a vertical (9:16) or square video of three minutes or less is automatically classified as a Short, regardless of any metadata. Conductor warns at approval rather than blocking, since this is usually intended.
- Uploads are resumable and Conductor checkpoints progress, so a retry resumes rather than restarting.
`

export default doc
