import { Config } from '../config.js'
import { apiGet, apiDelete, ApiError } from '../api.js'
import { resolveCreativeId } from '../../lib/creative-id.js'

const base = (config: Config) => `/api/v2/projects/${config.projectId}/marketing`

interface MediaUsage {
  creatives?: Array<{ displayId: string; name: string; state?: string }>
  posts?: Array<{ displayId: string; title: string; status?: string }>
}

const isNotFound = (err: unknown) => err instanceof ApiError && err.status === 404

/** Refuses (without calling DELETE) while any Creative or Post uses the Media; the server re-checks on delete. */
export async function deleteMedia(params: { mediaId: string }, config: Config): Promise<Record<string, unknown>> {
  const url = `${base(config)}/photos/${params.mediaId}`
  const usage = await apiGet<MediaUsage>(`${url}/usage`, config)
  const creatives = usage.creatives ?? []
  const posts = usage.posts ?? []
  if (creatives.length > 0 || posts.length > 0) {
    const parts: string[] = []
    if (creatives.length) parts.push(`Creatives ${creatives.map((c) => `${c.displayId} "${c.name}"`).join(', ')}`)
    if (posts.length) parts.push(`Posts ${posts.map((p) => `${p.displayId} "${p.title}"`).join(', ')}`)
    return {
      deleted: false,
      message: `Not deleted: ${params.mediaId} is used by ${parts.join(' and ')}. Remove it there first (delete_creative, or take it off the Post), then try again.`,
    }
  }
  try {
    await apiDelete(url, config)
  } catch (err) {
    if (err instanceof ApiError && err.status === 409) {
      return { deleted: false, message: `Not deleted: ${err.detail ?? err.message}` }
    }
    throw err
  }
  try {
    await apiGet(`${url}/usage`, config)
  } catch (err) {
    if (isNotFound(err)) return { deleted: true, message: `Deleted Media ${params.mediaId}; the stored file is removed.` }
    throw err
  }
  return { deleted: false, message: `Delete returned success but Media ${params.mediaId} is still readable — check list_creative_media.` }
}

export async function deleteCreative(params: { creativeId: string }, config: Config): Promise<Record<string, unknown>> {
  const id = await resolveCreativeId(params.creativeId, config)
  const url = `${base(config)}/creatives/${id}`
  try {
    await apiDelete(url, config)
  } catch (err) {
    if (err instanceof ApiError && err.status === 409) {
      return { deleted: false, message: `Not deleted: ${err.detail ?? err.message}` }
    }
    throw err
  }
  try {
    await apiGet(url, config)
  } catch (err) {
    if (isNotFound(err)) return { deleted: true, message: `Deleted Creative ${params.creativeId}. The Media it used is untouched.` }
    throw err
  }
  return { deleted: false, message: `Delete returned success but Creative ${params.creativeId} is still readable — call get_creative.` }
}
