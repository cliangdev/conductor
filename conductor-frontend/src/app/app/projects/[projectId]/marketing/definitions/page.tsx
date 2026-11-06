'use client'

// Metric definitions — /app/projects/{id}/marketing/definitions. A static sibling of the Area's other
// Marketing routes (overview, insights).

import Link from 'next/link'
import { useParams } from 'next/navigation'
import { ShieldCheckIcon } from 'lucide-react'
import { buttonVariants } from '@/components/ui/button'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { DefinitionsList } from '@/components/marketing/definitions/DefinitionsList'

export const dynamic = 'force-dynamic'

export default function MarketingDefinitionsPage() {
  const { projectId } = useParams<{ projectId: string }>()
  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[
          { label: 'Marketing' },
          { label: 'Overview', href: `/app/projects/${projectId}/marketing/overview` },
          { label: 'Definitions' },
        ]}
        title="Metric definitions"
        description="What every marketing number counts: the measure, where it comes from, what is left out and which time zone decides its day."
        actions={
          <Link
            prefetch={false}
            href={`/app/projects/${projectId}/marketing/verification`}
            className={buttonVariants({ variant: 'outline', size: 'sm' })}
          >
            <ShieldCheckIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
            Verification
          </Link>
        }
      />
      <DefinitionsList projectId={projectId} />
    </PageContainer>
  )
}
