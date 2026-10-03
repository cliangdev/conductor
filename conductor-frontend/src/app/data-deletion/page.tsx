import type { Metadata } from 'next'
import { CONTACT_EMAIL, LegalPage, OPERATOR_NAME } from '@/components/site/SiteChrome'

export const metadata: Metadata = {
  title: 'Data Deletion',
  description:
    'How to delete the data Conductor stores from your connected Facebook, Instagram and other accounts: disconnect in Conductor, remove Conductor on the platform, or email us.',
  alternates: { canonical: '/data-deletion' },
}

const UPDATED = 'October 2, 2026'

export default function DataDeletionPage() {
  return (
    <LegalPage title="Data Deletion" updated={UPDATED}>
      <p>
        This page explains how to delete the data Conductor, operated by {OPERATOR_NAME}, holds
        about an account you connected, such as a Facebook Page or an Instagram professional account. There are three ways, and you
        can use whichever suits you.
      </p>

      <h2 id="what-we-store">What we store</h2>
      <p>
        From a connected account, Conductor stores the access tokens (encrypted), the account or
        Page identifier and name, the permissions you granted, records of the content Conductor
        published for you, and readings of how that content performed. The{' '}
        <a href="/privacy">Privacy Policy</a> has the full detail.
      </p>

      <h2 id="disconnect">Option 1: Disconnect in Conductor</h2>
      <ol>
        <li>Sign in to Conductor and open the workspace the account is connected to.</li>
        <li>Choose <strong>Integrations</strong> in the sidebar, under Automation.</li>
        <li>Open the <strong>Meta</strong> connector, and select <strong>Disconnect</strong> on the connection.</li>
      </ol>
      <p>
        Disconnecting deletes the stored access tokens straight away, and Conductor stops
        publishing and reading through that account. Records of posts it already published, and
        their performance readings, stay with those Posts in your workspace until you delete the
        Posts or use Option 3. If a Post is still scheduled to go out through the account,
        Conductor asks you to unschedule it first. Workspace administrators and creators can
        disconnect an account.
      </p>

      <h2 id="remove-on-platform">Option 2: Remove Conductor on the platform</h2>
      <ul>
        <li><strong>Facebook:</strong> Settings &amp; privacy, then Settings, then Business integrations. Select Conductor and choose Remove.</li>
        <li><strong>Instagram:</strong> Settings, then Website permissions, then Apps and websites. Select Conductor and remove it.</li>
      </ul>
      <p>
        This stops Conductor&rsquo;s access immediately, because the token no longer works.
        Conductor is not notified, so the data it already stored stays until you also use Option 1
        or Option 3.
      </p>

      <h2 id="request-deletion">Option 3: Ask us to delete it</h2>
      <p>
        Email <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a> from your account address.
        We will delete the account, any workspace where you are the only administrator, and the
        connection tokens and performance readings that go with them, within 30 days. We confirm
        by email when it is done.
      </p>
    </LegalPage>
  )
}
