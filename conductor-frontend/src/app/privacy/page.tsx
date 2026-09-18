import type { Metadata } from 'next'
import { CONTACT_EMAIL, LegalPage } from '@/components/site/SiteChrome'

/**
 * TODO(legal): before the next platform submission, confirm with counsel (a) the operating legal
 * entity's name and (b) the governing-law jurisdiction in `/terms`. The substance below describes
 * what the product actually does today.
 */

export const metadata: Metadata = {
  title: 'Privacy Policy',
  description:
    'How Conductor collects, uses, shares, keeps and deletes the data in your workspace, including data from the third-party accounts you connect.',
  alternates: { canonical: '/privacy' },
}

const UPDATED = 'September 18, 2026'

export default function PrivacyPage() {
  return (
    <LegalPage title="Privacy Policy" updated={UPDATED}>
      <p>
        This policy explains what Conductor (&ldquo;Conductor&rdquo;, &ldquo;we&rdquo;,
        &ldquo;us&rdquo;) collects, why we collect it, who we share it with, how long we keep it,
        and how you get rid of it. It covers the Conductor web application, the API, the
        command-line tools, and the integrations you choose to connect.
      </p>

      <h2>Who this applies to</h2>
      <p>
        Conductor is a business product used by teams. Each team works inside a{' '}
        <strong>workspace</strong>, and the workspace is the boundary for all access: only people
        invited to it can see what is in it. If you use Conductor through a workspace someone else
        created, that workspace&rsquo;s administrators control its membership and its connected
        accounts.
      </p>

      <h2>What we collect</h2>
      <h3>Account information</h3>
      <p>
        When you sign in with Google we receive your email address, your display name and the URL
        of your profile image, and we store a Google account identifier so we can recognise you the
        next time you sign in. We never receive or store your Google password.
      </p>

      <h3>Content you and your agents create</h3>
      <p>
        Work Items, documents, comments, reviews and approvals, knowledge pages, workflow
        definitions and their run history, agent configurations and transcripts, agent memories,
        and any files you upload. This content belongs to your workspace.
      </p>

      <h3>Connected third-party accounts</h3>
      <p>
        When you connect an account, say TikTok, Instagram, a Facebook Page, YouTube, Discord,
        GitHub or Google Drive, you authorise it on that platform&rsquo;s own sign-in screen. We
        store the resulting access and refresh tokens in encrypted form, along with the account or
        page identifier, its display name, and the permissions you granted. We ask for only the
        permissions the feature you turned on actually needs.
      </p>

      <h3>Published content and how it performed</h3>
      <p>
        For content that Conductor publishes for you, we store where it went, when it was
        published, the platform&rsquo;s identifier for the resulting post, and readings of that
        post&rsquo;s public performance counters, taken on a schedule. Those counters are things
        like view, like, comment and share counts. We read them only for posts Conductor itself
        published for your workspace. We do not read, index or store your other posts, your
        followers, your direct messages, or anyone else&rsquo;s content.
      </p>

      <h3>Model provider credentials</h3>
      <p>
        Conductor runs on your own model provider keys. Any API key you supply is stored using
        envelope encryption with a managed key service, and is used only to run the agents in your
        workspace.
      </p>

      <h3>Technical and usage data</h3>
      <p>
        Server logs, which record IP address, user agent, request path, timestamps and error
        details; records of integration deliveries and retries; and authentication cookies. Sign-in
        uses a single HTTP-only session cookie. We do not use advertising or cross-site tracking
        cookies.
      </p>

      <h2>How we use it</h2>
      <ul>
        <li>To run the product: sign you in, limit access to your workspace, run your workflows and agents, and deliver approved content to the destinations you picked.</li>
        <li>To show your workspace how its published content performed, and to write the weekly summaries your agents read.</li>
        <li>To keep the service secure and reliable, which covers abuse prevention, rate limiting, debugging and backups.</li>
        <li>To send the transactional email you asked for: workspace invitations, review notifications and security notices.</li>
      </ul>
      <p>
        <strong>We do not sell your data, rent it, or hand it to data brokers.</strong> We do not
        use your workspace content or your connected-platform data for advertising or ad targeting,
        and we do not use it to train machine-learning models.
      </p>

      <h2>Who we share it with</h2>
      <p>We use a small set of processors, each for one specific job:</p>
      <ul>
        <li><strong>Google Cloud Platform</strong> hosts the application and stores files.</li>
        <li><strong>Google Firebase Authentication</strong> handles sign-in.</li>
        <li><strong>A managed PostgreSQL provider</strong> runs the primary database.</li>
        <li><strong>Resend</strong> delivers transactional email.</li>
        <li><strong>The AI model providers you configure</strong> receive the prompts and content your agents process, under that provider&rsquo;s own terms. You choose which provider by supplying its key.</li>
        <li><strong>The platforms you connect</strong> receive the content you approve for publication, at the destination you selected.</li>
      </ul>
      <p>
        We may also disclose information where the law requires it, or to protect the rights,
        safety or property of Conductor, our users or the public. If Conductor is ever part of a
        merger or acquisition, we will give you notice before your information becomes subject to a
        different policy.
      </p>

      <h2>Data from TikTok and other connected platforms</h2>
      <p>
        We use data from a platform&rsquo;s API only to provide the features you turned on in your
        workspace, and only for as long as your connection stays active. In TikTok&rsquo;s case
        that means two things and nothing else. First, uploading and publishing the content your
        workspace approved to the TikTok account your workspace connected. Second, reading back the
        public performance counters of those published videos, so your workspace can see how its
        own content did. We show that information only to people in your workspace, inside
        Conductor. We do not embed it, syndicate it, or display your posts or their metrics
        publicly. We do not share it with third parties, and we handle it according to the relevant
        platform&rsquo;s developer terms and policies.
      </p>

      <h2>How long we keep it</h2>
      <ul>
        <li><strong>Workspace content</strong> stays until you delete it or delete the workspace.</li>
        <li><strong>Connection tokens</strong> stay until you disconnect the account or revoke access on the platform. At that point the stored tokens are deleted.</li>
        <li><strong>Performance readings</strong> are deleted with the Post they belong to, or when the connection they were read through is removed.</li>
        <li><strong>Server logs</strong> are kept for a limited operational period, currently up to 30 days, then rotated out.</li>
        <li><strong>Backups</strong> are kept on a rolling schedule and overwritten in the ordinary course.</li>
      </ul>

      <h2>Your choices and rights</h2>
      <ul>
        <li><strong>See your data.</strong> Everything in your workspace is visible in the application, and reachable through the API and the CLI.</li>
        <li><strong>Disconnect an account.</strong> Go to Settings, then Integrations, and disconnect any connection whenever you want. You can also revoke Conductor&rsquo;s access from the platform&rsquo;s own security settings.</li>
        <li><strong>Delete content.</strong> Delete individual Work Items, documents or knowledge pages in the application.</li>
        <li><strong>Delete your account or workspace.</strong> Email us at{' '}
          <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a> from your account address. We will
          delete the account, any workspace where you are the only administrator, and the
          connection tokens and performance readings that go with them, within 30 days.
        </li>
        <li>
          Depending on where you live you may have further rights, such as access, correction,
          portability, erasure or objection. Write to us at the address above and we will honour
          them.
        </li>
      </ul>

      <h2>Security</h2>
      <p>
        Traffic is encrypted in transit with TLS. Third-party tokens and model provider keys are
        encrypted at rest. Access to a workspace depends on membership, and we check it on every
        request. Sessions use HTTP-only cookies. No system is perfectly secure, and we will notify
        affected users of any breach that materially affects them.
      </p>

      <h2>International transfers</h2>
      <p>
        Conductor is operated from the United States and your information is processed there. If you
        use Conductor from another country, you are transferring your information to the United
        States.
      </p>

      <h2>Children</h2>
      <p>
        Conductor is a workplace product and is not aimed at children. We do not knowingly collect
        information from anyone under 16. If you think a child has given us information, contact us
        and we will delete it.
      </p>

      <h2>Changes</h2>
      <p>
        We will update this page when our practices change, and revise the &ldquo;last
        updated&rdquo; date at the top. We will announce material changes in the application or by
        email.
      </p>

      <h2>Contact</h2>
      <p>
        Questions, requests or complaints go to{' '}
        <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>.
      </p>
    </LegalPage>
  )
}
