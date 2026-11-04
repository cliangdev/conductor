import { Command } from 'commander'
import { getConfig, resolveProject } from '../mcp/config.js'
import { resolveCreativeId } from '../lib/creative-id.js'
import { renderCreative } from '../lib/creative-render.js'
import { attachSlideshowSpec } from '../mcp/tools/slideshow-spec.js'

/**
 * Headless runs (a workflow's docker step) have no ~/.conductor/config.json; the step supplies
 * CONDUCTOR_API_URL, CONDUCTOR_API_KEY and CONDUCTOR_PROJECT_ID instead. When config can't be loaded,
 * say which of those is missing rather than only "run conductor login".
 */
export function describeConfigFailure(err: unknown, env: NodeJS.ProcessEnv = process.env): string {
  const message = err instanceof Error ? err.message : String(err)
  if (!message.startsWith('Config not found')) return message
  const missing = ['CONDUCTOR_API_URL', 'CONDUCTOR_API_KEY'].filter((name) => !env[name])
  if (missing.length === 0) return message
  return (
    `${message}. Without a login, set the environment variable${missing.length > 1 ? 's' : ''} ` +
    `${missing.join(' and ')} (and CONDUCTOR_PROJECT_ID) instead.`
  )
}

/**
 * The config a creative command runs with, or undefined after printing why it cannot (and setting a failing exit
 * code): no login, or a git repository not linked to a Conductor project, or no project selected.
 */
function loadCreativeConfig(): ReturnType<typeof getConfig> | undefined {
  let config
  try {
    config = getConfig()
  } catch (err) {
    console.error(describeConfigFailure(err))
    process.exitCode = 1
    return undefined
  }

  const resolution = resolveProject(config)
  if (resolution.mismatch) {
    console.error(
      `Refusing to act: the current directory (${process.cwd()}) is a git repository not linked to any ` +
        'configured Conductor project. Run `conductor init` here, or set CONDUCTOR_PROJECT_ID.'
    )
    process.exitCode = 1
    return undefined
  }
  config.projectId = resolution.projectId
  if (!config.projectId) {
    console.error('No project selected — set CONDUCTOR_PROJECT_ID (or run `conductor login`).')
    process.exitCode = 1
    return undefined
  }
  return config
}

export function registerCreative(program: Command): void {
  const creative = program.command('creative').description('Work with Conductor Creatives locally: render them with Playwright, or attach a slideshow spec')

  creative
    .command('render <creativeId>')
    .description('Render a Creative to upload-ready JPEG frames (or a preview contact sheet) using a local browser')
    .option('--preview', 'Render a preview contact sheet instead of full-size frames')
    .option('--renderer <name>', 'Attribution tag stored on the render (default: cli)')
    .option('--workflow-run-id <id>', 'Attribute this render to a Workflow run')
    .addHelpText(
      'after',
      `
Examples:
  conductor creative render 12a
  conductor creative render 12a --preview
  conductor creative render 12a --renderer workflow --workflow-run-id run_123`
    )
    .action(async (creativeId: string, options: { preview?: boolean; renderer?: string; workflowRunId?: string }) => {
      const config = loadCreativeConfig()
      if (!config) return

      try {
        const result = await renderCreative(
          {
            creativeId: await resolveCreativeId(creativeId, config),
            previewOnly: !!options.preview,
            renderer: options.renderer ?? 'cli',
            workflowRunId: options.workflowRunId,
          },
          config,
          (...args: unknown[]) => console.log(...args)
        )

        console.log(`render ${result.renderId ?? '(none)'}: ${result.state ?? (result.ok ? 'SUCCEEDED' : 'FAILED')}`)
        for (const frame of result.frames) {
          const label = frame.sequenceIndex != null ? `${frame.placementKey}[${frame.sequenceIndex}]` : frame.placementKey
          const size = frame.width && frame.height ? `${frame.width}x${frame.height}` : ''
          // CLIP/MOTION frames are videos, not JPEGs — durationSeconds/hasAudio/posterUrl are only ever set on those.
          const duration = frame.durationSeconds != null ? `${frame.durationSeconds}s${frame.hasAudio ? ' audio' : ''}` : ''
          const parts = [label, size, duration, frame.url ?? ''].filter(Boolean)
          console.log(`  ${parts.join('  ')}`)
          if (frame.posterUrl) console.log(`    poster: ${frame.posterUrl}`)
        }
        if (result.error) console.error(result.error)
        if (!result.ok || result.state === 'FAILED') {
          process.exitCode = 1
        }
      } catch (err) {
        console.error(err instanceof Error ? err.message : String(err))
        process.exitCode = 1
      }
    })

  creative
    .command('attach-spec <creativeId> <specPath>')
    .description('Store a slideshow spec on a CAROUSEL Creative committed without one, so its text becomes editable')
    .option('--force', 'Attach even when slides differ from a fresh render, or when the Creative already has a spec')
    .addHelpText(
      'after',
      `
Re-composes the spec on this machine at each ratio the Creative has, compares every slide with the stored image
(SSIM) and refuses below 0.95 unless --force. Then uploads the spec's images and saves the spec.
<specPath> is a source spec (relative image paths resolve against its folder) or a compose_slideshow output spec.json.

Examples:
  conductor creative attach-spec 12a ./specs/nov02.json
  conductor creative attach-spec 12a .conductor/slideshows/20261102-101500-nov02/spec.json --force`
    )
    .action(async (creativeId: string, specPath: string, options: { force?: boolean }) => {
      const config = loadCreativeConfig()
      if (!config) return
      try {
        const result = await attachSlideshowSpec(
          { creativeId: await resolveCreativeId(creativeId, config), specPath, force: !!options.force },
          config,
          { log: (message) => console.log(message) }
        )
        if (!result.ok) {
          console.error(result.error)
          process.exitCode = 1
          return
        }
        console.log(result.nextStep)
        if (result.compared > 0) {
          console.log(`  compared ${result.compared} slides, lowest similarity ${result.minSimilarity}${result.forced ? ' (below the threshold; attached with --force)' : ''}`)
        }
        if (result.skipped.length) console.log(`  not compared: ${result.skipped.join('; ')}`)
        console.log(`  uploaded ${result.images} image${result.images === 1 ? '' : 's'}${result.slideStyleSet ? '; slide style recorded from the spec' : ''}`)
        for (const warning of result.warnings) console.log(`  warning: ${warning}`)
      } catch (err) {
        console.error(err instanceof Error ? err.message : String(err))
        process.exitCode = 1
      }
    })
}
