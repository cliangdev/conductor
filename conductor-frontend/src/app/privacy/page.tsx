import type { Metadata } from 'next'
import { CONTACT_EMAIL, LegalPage } from '@/components/site/SiteChrome'

/**
 * TODO(legal): before the next platform submission, confirm with counsel (a) the operating legal
 * entity's name, (b) the governing-law jurisdiction in `/terms`, and (c) the contact address in
 * `CONTACT_EMAIL`. The substance below describes what the product actually does today.
 */

export const metadata: Metadata = {
  title: 'Privacy Policy',
  description:
    'How Conductor collects, uses, shares, retains, and deletes the data in your workspace, including data from the third-party accounts you connect.',
  alternates: { canonical: '/privacy' },
}

const UPDATED = 'September 18, 2026'

export default function PrivacyPage() {
  return (
    <LegalPage title="Privacy Policy" updated={UPDATED}>
      <p>
        This policy explains what Conductor (&ldquo;Conductor&rdquo;, &ldquo;we&rdquo;,
        &ldquo;us&rdquo;) collects, why, who we share it with, how long we keep it, and how you
        remove it. It covers the Conductor web application, API, command-line tools, and the
        integrations you choose to connect.
      </p>

      <h2>Who this applies to</h2>
      <p>
        Conductor is a business product used by teams. Each team works inside a{' '}
        <strong>workspace</strong>, and a workspace is the boundary for all access: only people
        invited to a workspace can see its content. If you use Conductor through a workspace
        someone else created, that workspace&rsquo;s administrators control its membership and its
        connected accounts.
      </p>

      <h2>What we collect</h2>
      <h3>Account information</h3>
      <p>
        When you sign in with Google we receive your email address, display name, and profile image
        URL from Google&rsquo;s identity service, and we store a Google account identifier so we
        can recognise you on your next sign-in. We never receive or store your Google password.
      </p>

      <h3>Content you and your agents create</h3>
      <p>
        Work Items, documents, comments, reviews and approvals, knowledge pages, workflow
        definitions and run history, agent configurations and transcripts, agent memories, and any
        files you upload. This content belongs to your workspace.
      </p>

      <h3>Connected third-party accounts</h3>
      <p>
        When you connect an account — for example TikTok, Instagram, a Facebook Page, YouTube,
        Discord, GitHub, or Google Drive — you authorise it through that platform&rsquo;s own OAuth
        screen. We store the resulting access and refresh tokens in encrypted form, the account or
        page identifier and display name, and the scopes you granted. We store only the scopes the
        feature you enabled requires.
      </p>

      <h3>Published content and its performance</h3>
      <p>
        For content that Conductor publishes on your behalf we store the destination, the time of
        publication, the platform&rsquo;s identifier for the resulting post, and periodic snapshots
        of that post&rsquo;s public performance counters (for example view, like, comment, and
        share counts). We read these counters only for posts Conductor itself published for your
        workspace. We do not read, index, or store your other posts, your followers, your direct
        messages, or anyone else&rsquo;s content.
      </p>

      <h3>Model provider credentials</h3>
      <p>
        Conductor runs on a bring-your-own-key model. API keys you supply for AI providers are
        stored using envelope encryption with a managed key service and are used only to run the
        agents in your workspace.
      </p>

      <h3>Technical and usage data</h3>
      <p>
        Server logs (IP address, user agent, request path, timestamps, error details), integration
        delivery and retry records, and authentication cookies. We use a single HTTP-only session
        cookie for sign-in; we do not use advertising or cross-site tracking cookies.
      </p>

      <h2>How we use it</h2>
      <ul>
        <li>To operate the product: authenticate you, scope access to your workspace, run your workflows and agents, and deliver your approved content to the destinations you chose.</li>
        <li>To show your workspace how its published content performed, and to generate the written performance reports your agents read.</li>
        <li>To keep the service secure and reliable: abuse prevention, rate limiting, debugging, and backups.</li>
        <li>To send transactional email you asked for: workspace invitations, review notifications, and security notices.</li>
      </ul>
      <p>
        <strong>We do not sell your data, rent it, or share it with data brokers.</strong> We do not
        use your workspace content or your connected-platform data for advertising or ad targeting,
        and we do not use it to train machine-learning models.
      </p>

      <h2>Who we share it with</h2>
      <p>We use a small set of processors, each for a specific job:</p>
      <ul>
        <li><strong>Google Cloud Platform</strong> — application hosting and file storage.</li>
        <li><strong>Google Firebase Authentication</strong> — sign-in.</li>
        <li><strong>A managed PostgreSQL provider</strong> — the primary database.</li>
        <li><strong>Resend</strong> — transactional email delivery.</li>
        <li><strong>AI model providers you configure</strong> — the prompts and content your agents process are sent to the provider whose key you supplied, under that provider&rsquo;s terms.</li>
        <li><strong>Platforms you connect</strong> — content you approve for publication is sent to the destination account you selected.</li>
      </ul>
      <p>
        We may also disclose information where required by law, or to protect the rights, safety, or
        property of Conductor, our users, or the public. If Conductor is involved in a merger or
        acquisition, we will give notice before your information becomes subject to a different
        policy.
      </p>

      <h2>Data from TikTok and other connected platforms</h2>
      <p>
        Data we obtain through a platform&rsquo;s API is used only to provide the features you
        enabled in your workspace, and only for as long as your connection remains active. We use
        TikTok data solely to (a) upload and publish the content your workspace approved to the
        TikTok account your workspace connected, and (b) read back the public performance counters
        of those published videos so your workspace can see how its own content performed. We
        display that information only to members of your workspace inside Conductor. We do not
        embed, syndicate, or publicly display your posts or their metrics on our website or
        anywhere else, we do not share it with third parties, and we handle it in accordance with
        the relevant platform&rsquo;s developer terms and policies.
      </p>

      <h2>How long we keep it</h2>
      <ul>
        <li><strong>Workspace content</strong> — until you delete it or delete the workspace.</li>
        <li><strong>Connection tokens</strong> — until you disconnect the account or revoke access from the platform, at which point the stored tokens are deleted.</li>
        <li><strong>Performance snapshots</strong> — deleted with the Post they belong to, or when the connection they were read through is removed.</li>
        <li><strong>Server logs</strong> — retained for a limited operational period (currently up to 30 days) and then rotated out.</li>
        <li><strong>Backups</strong> — retained on a rolling schedule and overwritten in the ordinary course.</li>
      </ul>

      <h2>Your choices and rights</h2>
      <ul>
        <li><strong>See your data</strong> — everything in your workspace is visible in the application, and reachable through the API and CLI.</li>
        <li><strong>Disconnect an account</strong> — Settings → Integrations, on any connection, at any time. You can also revoke Conductor&rsquo;s access from the platform&rsquo;s own security settings.</li>
        <li><strong>Delete content</strong> — delete individual Work Items, documents, or knowledge pages in the application.</li>
        <li><strong>Delete your account or workspace</strong> — email us at{' '}
          <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a> from your account address and we
          will delete the account, its workspaces where you are the sole administrator, and the
          associated connection tokens and snapshots within 30 days.
        </li>
        <li>
          Depending on where you live you may have additional rights — access, correction,
          portability, erasure, or objection. Write to us at the address above and we will honour
          them.
        </li>
      </ul>

      <h2>Security</h2>
      <p>
        Traffic is encrypted in transit with TLS. Third-party tokens and model provider keys are
        encrypted at rest. Access to a workspace is gated on membership and checked on every
        request. Sessions use HTTP-only cookies. No system is perfectly secure, and we will notify
        affected users of a breach that materially affects them.
      </p>

      <h2>International transfers</h2>
      <p>
        Conductor is operated from the United States and your information is processed there. If you
        use Conductor from another country, you are transferring your information to the United
        States.
      </p>

      <h2>Children</h2>
      <p>
        Conductor is a workplace product and is not directed to children. We do not knowingly
        collect information from anyone under 16. If you believe a child has provided us
        information, contact us and we will delete it.
      </p>

      <h2>Changes</h2>
      <p>
        We will update this page when our practices change and revise the &ldquo;last
        updated&rdquo; date above. Material changes will be announced in the application or by
        email.
      </p>

      <h2>Contact</h2>
      <p>
        Questions, requests, or complaints:{' '}
        <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>.
      </p>
    </LegalPage>
  )
}
