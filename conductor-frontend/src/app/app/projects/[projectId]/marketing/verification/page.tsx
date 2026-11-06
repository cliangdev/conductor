'use client'

// Verification ledger — /app/projects/{id}/marketing/verification. A static sibling of the Area's other
// Marketing routes (overview, insights): it reads across channels, so it belongs to the Area.

import Link from 'next/link'
import { useParams } from 'next/navigation'
import { BookOpenIcon } from 'lucide-react'
import { buttonVariants } from '@/components/ui/button'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { VerificationLedger } from '@/components/marketing/verification/VerificationLedger'

export const dynamic = 'force-dynamic'

export default function MarketingVerificationPage() {
  const { projectId } = useParams<{ projectId: string }>()
  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[
          { label: 'Marketing' },
          { label: 'Overview', href: `/app/projects/${projectId}/marketing/overview` },
          { label: 'Verification' },
        ]}
        title="Verification"
        description="Each metric beside a second source, and which figure to trust. A metric nothing else reports is unchecked, not verified."
        actions={
          <Link
            prefetch={false}
            href={`/app/projects/${projectId}/marketing/definitions`}
            className={buttonVariants({ variant: 'outline', size: 'sm' })}
          >
            <BookOpenIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
            Definitions
          </Link>
        }
      />
      <VerificationLedger projectId={projectId} />
    </PageContainer>
  )
}
