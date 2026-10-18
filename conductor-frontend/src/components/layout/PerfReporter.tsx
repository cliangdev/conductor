'use client'

import { useEffect, useRef } from 'react'
import { usePathname } from 'next/navigation'
import { useReportWebVitals } from 'next/web-vitals'
import { currentPage, enqueue, startNavigation } from '@/lib/telemetry'

/** Mounted once in the authenticated app layout: reports web vitals and tracks navigation starts. */
export function PerfReporter() {
  const pathname = usePathname()
  const first = useRef(true)

  useEffect(() => {
    // The first render is the document load (navigation start = timeOrigin); later path changes are
    // client-side navigations that start now.
    if (first.current) {
      first.current = false
      return
    }
    startNavigation()
  }, [pathname])

  useReportWebVitals((metric) => {
    enqueue({
      type: 'web_vital',
      name: metric.name,
      value: Math.round(metric.name === 'CLS' ? metric.value * 1000 : metric.value),
      rating: metric.rating,
      page: currentPage(),
    })
  })

  return null
}
