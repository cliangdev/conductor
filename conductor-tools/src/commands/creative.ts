import { Command } from 'commander'
import { getConfig, resolveProject } from '../mcp/config.js'
import { resolveCreativeId } from '../lib/creative-id.js'
import { renderCreative } from '../lib/creative-render.js'

export function registerCreative(program: Command): void {
  const creative = program.command('creative').description('Render Conductor Creatives locally with Playwright')

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
      let config
      try {
        config = getConfig()
      } catch (err) {
        console.error(err instanceof Error ? err.message : String(err))
        process.exitCode = 1
        return
      }

      const resolution = resolveProject(config)
      if (resolution.mismatch) {
        console.error(
          `Refusing to act: the current directory (${process.cwd()}) is a git repository not linked to any ` +
            'configured Conductor project. Run `conductor init` here, or set CONDUCTOR_PROJECT_ID.'
        )
        process.exitCode = 1
        return
      }
      config.projectId = resolution.projectId

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
}
