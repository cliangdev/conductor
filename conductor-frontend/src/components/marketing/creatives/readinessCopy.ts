// Plain-language copy for the Creative readiness checklist. The server sends a lowercase fragment per
// item ("10 of 10 slide images have not been checked for burned-in text"); this turns each key into a
// short title, a one-line explanation and, where the editor can fix it in place, an action label.
// Unknown keys fall back to the server's message, sentence-cased.

import type { CreativeReadinessItem } from './types'

export interface ReadinessCopy {
  title: string
  explanation?: string
  /** Label of the in-place fix, when the editor offers one for this key. */
  action?: string
}

export function sentenceCase(text: string): string {
  const t = text.trim()
  return t ? t.charAt(0).toUpperCase() + t.slice(1) : t
}

function plural(n: number, one: string, many: string): string {
  return n === 1 ? one : many
}

/** The "N" in "N of M ..." messages, or null. */
function leadingCount(message: string): number | null {
  const m = /^(\d+) of \d+/.exec(message)
  return m ? Number(m[1]) : null
}

const MEDIA_SOURCE_EXPLANATION =
  'Every image, clip and track needs a recorded source and licence before it can be published.'

export function readinessCopy(item: CreativeReadinessItem): ReadinessCopy {
  const message = item.message ?? ''
  switch (item.key) {
    case 'slides':
      return { title: 'Choose at least one slide', explanation: 'A carousel needs images to publish.', action: 'Choose slides' }
    case 'caption':
      return { title: 'Add a caption', explanation: 'The Post needs primary text to publish.', action: 'Add caption' }
    case 'altText':
      return {
        title: 'Alt text is missing',
        explanation: 'Describe what the image shows for people who cannot see it. A slideshow needs one per slide.',
        action: 'Add alt text',
      }
    case 'altTextStale': {
      const m = /^slides? ([\d, ]+?) alt text still quotes/.exec(message)
      const which = m ? m[1].trim() : null
      const several = which ? which.includes(',') : false
      return {
        title: which
          ? `Alt text on ${several ? 'slides' : 'slide'} ${which} still ${several ? 'quote' : 'quotes'} the old text`
          : 'Alt text still quotes the old text',
        explanation: 'The slide text changed. Update the alt text so it matches what is on the slide now.',
      }
    }
    case 'photoChecked': {
      if (/no photo chosen/i.test(message)) {
        return { title: 'Choose a photo', explanation: 'This Creative has no photo yet.' }
      }
      const n = leadingCount(message) ?? 1
      return {
        title: `Look over ${n} ${plural(n, 'photo', 'photos')} for text in the image`,
        explanation: 'Signs, logos or watermarks inside a photo clash with the headline Conductor adds.',
        action: "I've checked them",
      }
    }
    case 'mediaProvenance': {
      const n = leadingCount(message)
      return {
        title: n === null ? 'Media is missing a source and licence' : `${n} ${plural(n, 'image is', 'images are')} missing a source and licence`,
        explanation: MEDIA_SOURCE_EXPLANATION,
      }
    }
    case 'photoProvenance':
      return { title: 'The photo is missing a source and licence', explanation: MEDIA_SOURCE_EXPLANATION }
    case 'clipProvenance':
      return { title: 'The background clip is missing a source and licence', explanation: MEDIA_SOURCE_EXPLANATION }
    case 'audioProvenance':
      return {
        title: 'The audio track is missing a source and licence',
        explanation: 'Music needs a recorded source and licence so we know it is cleared to use.',
      }
    case 'clip':
      return { title: 'Choose a clip', explanation: 'A video Creative needs at least one clip.' }
    case 'motionBackground':
      return { title: 'Choose a background clip', explanation: 'The background is set to a clip, but none is chosen.' }
    case 'aiDisclosure': {
      const lead = message.split(':')[0]
      return {
        title: sentenceCase(lead),
        explanation: "Turn on each platform's AI-generated label when you publish. The Post page reminds you.",
      }
    }
    default: {
      // Unknown key: the server's own words, split at the first colon into title and explanation.
      const i = message.indexOf(':')
      if (i > 0) return { title: sentenceCase(message.slice(0, i)), explanation: sentenceCase(message.slice(i + 1)) }
      return { title: sentenceCase(message) || item.key }
    }
  }
}
