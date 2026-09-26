import type { Metadata } from 'next'
import { Inter } from 'next/font/google'
import './globals.css'
import { AuthProvider } from '@/contexts/AuthContext'
import { ToastProvider } from '@/components/ui/toast'
import { ThemeProvider } from '@/components/providers/ThemeProvider'
import { resolveSiteUrl } from '@/lib/site-url'

const inter = Inter({ subsets: ['latin'], variable: '--font-sans' })

const siteUrl = resolveSiteUrl()
const description =
  'A coordination platform for teams that work with AI agents. Agents draft the work. Your team approves it. Conductor ships it.'

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: {
    default: 'Conductor',
    template: '%s · Conductor',
  },
  description,
  openGraph: {
    type: 'website',
    siteName: 'Conductor',
    title: 'Conductor',
    description,
    url: '/',
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Conductor',
    description,
  },
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={inter.variable} suppressHydrationWarning>
      <body className="font-sans antialiased">
        <ThemeProvider>
          <AuthProvider>
            <ToastProvider>{children}</ToastProvider>
          </AuthProvider>
        </ThemeProvider>
      </body>
    </html>
  )
}
