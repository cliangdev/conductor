import { ImageResponse } from 'next/og'

// Route segment config — this image is static and identical for every link.
export const alt = 'Conductor — Team PRD collaboration platform'
export const size = { width: 1200, height: 630 }
export const contentType = 'image/png'

// Brand teal, matching --primary (174 62% 38%) in globals.css.
const TEAL = '#249488'
const TEAL_DARK = '#0f3d39'

export default function OpengraphImage() {
  return new ImageResponse(
    (
      <div
        style={{
          width: '100%',
          height: '100%',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          padding: '96px',
          backgroundColor: TEAL_DARK,
          backgroundImage: `linear-gradient(135deg, ${TEAL_DARK} 0%, ${TEAL} 100%)`,
          color: '#ffffff',
          fontFamily: 'sans-serif',
        }}
      >
        {/* Brand mark + wordmark */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '28px' }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: '96px',
              height: '96px',
              borderRadius: '24px',
              backgroundColor: 'rgba(255,255,255,0.14)',
              border: '2px solid rgba(255,255,255,0.35)',
              fontSize: '60px',
              fontWeight: 800,
            }}
          >
            C
          </div>
          <div style={{ fontSize: '84px', fontWeight: 800, letterSpacing: '-0.02em' }}>
            Conductor
          </div>
        </div>

        <div
          style={{
            marginTop: '40px',
            fontSize: '44px',
            fontWeight: 600,
            lineHeight: 1.25,
            maxWidth: '900px',
            color: 'rgba(255,255,255,0.92)',
          }}
        >
          Team PRD collaboration platform
        </div>

        <div
          style={{
            marginTop: '20px',
            fontSize: '30px',
            color: 'rgba(255,255,255,0.7)',
          }}
        >
          Review, approve, and ship product requirements together.
        </div>
      </div>
    ),
    { ...size }
  )
}
