import Link from "next/link";
import { SectionHeading, SectionLabel, Tick, linkCls } from "./primitives";

/** What Conductor does with a YouTube channel a workspace connects — stated on the homepage because
 * Google's OAuth review reads the homepage, not just the privacy policy, for the app's purpose. */
const YOUTUBE_USES = [
  "Upload a video your team approved to your channel, with the title, description, privacy setting and publish time you chose.",
  "Read your channel’s name and ID, so you can confirm which channel the workspace publishes to.",
  "Read views, likes, comments, watch time and average view percentage for the videos Conductor uploaded, and show them only to your workspace.",
  "Set a video it uploaded back to private when your team unschedules it, and add it to the playlists you chose.",
];

const TRUST_POINTS = [
  "You connect each account through the platform’s own sign-in screen, and you can disconnect it any time from Integrations.",
  "Conductor only publishes what a person in your workspace approved.",
  "It only reads results for posts it published for you, and shows them only to your workspace.",
  "Tokens and model keys are stored encrypted and scoped to your workspace.",
];

const para = "max-w-[820px] text-[13.5px] leading-[1.7] text-foreground-muted";

function YouTubeCard() {
  return (
    <div id="google-data" className="mt-14 scroll-mt-20 rounded-lg border border-border bg-surface p-5 sm:p-7">
      <SectionLabel>Google user data</SectionLabel>
      <h3 className="mt-2.5 text-[20px] font-semibold tracking-[-0.01em] text-foreground">
        How Conductor uses YouTube
      </h3>
      <p className={`mt-3 ${para}`}>
        Conductor uses YouTube API Services so a team can publish the videos it approved to its own
        YouTube channel and see how they performed. When a member of your workspace connects a
        channel through Google&rsquo;s sign-in screen, Conductor will only:
      </p>
      <div className="mt-4 grid gap-x-10 gap-y-3 [grid-template-columns:repeat(auto-fit,minmax(min(100%,300px),1fr))]">
        {YOUTUBE_USES.map((use) => (
          <Tick key={use} small>
            {use}
          </Tick>
        ))}
      </div>
      <p className={`mt-4 ${para}`}>
        Conductor does not read your other videos, subscribers or comment text, does not sell or
        share YouTube data, does not use it for advertising, and does not use it to train AI models.
        Conductor&rsquo;s use and transfer of information received from Google APIs adheres to the{" "}
        <a
          href="https://developers.google.com/terms/api-services-user-data-policy"
          target="_blank"
          rel="noopener noreferrer"
          className={linkCls}
        >
          Google API Services User Data Policy
        </a>
        , including the Limited Use requirements. You can disconnect a channel in Integrations, or
        revoke access any time at{" "}
        <a
          href="https://myaccount.google.com/connections"
          target="_blank"
          rel="noopener noreferrer"
          className={linkCls}
        >
          myaccount.google.com/connections
        </a>
        .
      </p>
      <p className={`mt-3 ${para}`}>
        Conductor is for business marketing and engineering work. It does not generate images or
        video of people: its creative tools lay out the photos, clips, logo and text your team
        uploads. Sexual content, including AI-generated non-consensual intimate imagery, is
        prohibited under our{" "}
        <Link href="/terms" className={linkCls}>
          Terms
        </Link>
        .
      </p>
    </div>
  );
}

export function Trust() {
  return (
    <section id="trust" className="scroll-mt-14 border-b border-border">
      <div className="mx-auto max-w-6xl px-5 py-16 sm:py-20">
        <SectionLabel>Trust</SectionLabel>
        <SectionHeading>Your accounts stay yours</SectionHeading>
        <div className="mt-9 grid gap-x-10 gap-y-4 [grid-template-columns:repeat(auto-fit,minmax(min(100%,300px),1fr))]">
          {TRUST_POINTS.map((p) => (
            <Tick key={p}>{p}</Tick>
          ))}
        </div>
        <p className="mt-6 flex flex-wrap gap-x-5 gap-y-2 text-[13px]">
          <Link href="/privacy" className={linkCls}>
            Privacy Policy
          </Link>
          <Link href="/data-deletion" className={linkCls}>
            Data Deletion
          </Link>
        </p>
        <YouTubeCard />
      </div>
    </section>
  );
}
