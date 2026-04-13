import { spawn, spawnSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import axios from 'axios';
import { Job, updateJobStatus, removeJob } from './job-store';
import { startLogStreamer } from './log-streamer';

export function buildContainerName(runId: string, jobId: string): string {
  return `conductor-${runId}-${jobId}`;
}

export function buildVolumeName(runId: string, jobId: string): string {
  return `conductor-vol-${runId}-${jobId}`;
}

function runCommand(cmd: string, args: string[]): Promise<{ exitCode: number; stdout: string; stderr: string }> {
  return new Promise((resolve) => {
    const proc = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    proc.stdout.on('data', (d: Buffer) => { stdout += d.toString(); });
    proc.stderr.on('data', (d: Buffer) => { stderr += d.toString(); });
    proc.on('close', (code) => {
      resolve({ exitCode: code ?? 1, stdout, stderr });
    });
  });
}

export async function createVolume(volumeName: string): Promise<void> {
  const result = await runCommand('docker', ['volume', 'create', volumeName]);
  if (result.exitCode !== 0) {
    throw new Error(`Failed to create volume ${volumeName}: ${result.stderr}`);
  }
}

export async function startContainer(
  containerName: string,
  volumeName: string,
  image: string,
  env: Record<string, string>
): Promise<void> {
  const envArgs: string[] = [];
  for (const [key, value] of Object.entries(env)) {
    envArgs.push('-e', `${key}=${value}`);
  }

  const result = await runCommand('docker', [
    'run', '-d',
    '--name', containerName,
    '-v', `${volumeName}:/conductor/workspace`,
    '--rm=false',
    ...envArgs,
    image,
  ]);

  if (result.exitCode !== 0) {
    throw new Error(`Failed to start container ${containerName}: ${result.stderr}`);
  }
}

export function monitorContainer(job: Job): void {
  const proc = spawn('docker', ['wait', job.containerName], { stdio: ['ignore', 'pipe', 'pipe'] });

  let output = '';
  proc.stdout.on('data', (d: Buffer) => { output += d.toString(); });

  proc.on('close', async () => {
    const exitCode = parseInt(output.trim(), 10);
    const code = isNaN(exitCode) ? 1 : exitCode;

    if (code === 0) {
      await handleSuccessExit(job);
    } else {
      await handleFailureExit(job, code);
    }
  });
}

async function collectOutputs(job: Job): Promise<Record<string, string | null>> {
  const outputDir = `/tmp/outputs-${job.workerJobId}`;

  fs.mkdirSync(outputDir, { recursive: true });

  const copyResult = await runCommand('docker', [
    'cp',
    `${job.containerName}:/conductor/outputs/.`,
    outputDir,
  ]);

  if (copyResult.exitCode !== 0) {
    return {};
  }

  const outputs: Record<string, string | null> = {};
  try {
    const files = fs.readdirSync(outputDir);
    for (const file of files) {
      try {
        const filePath = path.join(outputDir, file);
        const stat = fs.statSync(filePath);
        if (stat.isFile()) {
          outputs[file] = fs.readFileSync(filePath, 'utf-8');
        }
      } catch {
        outputs[file] = null;
      }
    }
  } catch {
    // outputs directory may not exist in container; that's fine
  }

  try {
    fs.rmSync(outputDir, { recursive: true, force: true });
  } catch {
    // cleanup is best-effort
  }

  return outputs;
}

async function handleSuccessExit(job: Job): Promise<void> {
  updateJobStatus(job.workerJobId, 'SUCCESS', 0);

  const outputs = await collectOutputs(job);

  try {
    await axios.post(
      job.outputsCallbackUrl,
      { workerJobId: job.workerJobId, outputs },
      {
        headers: { Authorization: `Bearer ${job.ephemeralToken}` },
        timeout: 10000,
      }
    );
  } catch {
    // callback failure is non-fatal for cleanup
  }

  await cleanupContainer(job.containerName);
  removeJob(job.workerJobId);
}

async function handleFailureExit(job: Job, exitCode: number): Promise<void> {
  updateJobStatus(job.workerJobId, 'FAILED', exitCode);

  try {
    await axios.post(
      job.jobFailedCallbackUrl,
      {
        workerJobId: job.workerJobId,
        exitCode,
        reason: `Container exited with code ${exitCode}`,
      },
      {
        headers: { Authorization: `Bearer ${job.ephemeralToken}` },
        timeout: 10000,
      }
    );
  } catch {
    // callback failure is non-fatal for cleanup
  }

  await cleanupContainer(job.containerName);
  removeJob(job.workerJobId);
}

export async function cleanupContainer(containerName: string): Promise<void> {
  await runCommand('docker', ['rm', '-v', containerName]);
}

export async function launchJob(job: Job): Promise<void> {
  await createVolume(job.volumeName);
  await startContainer(job.containerName, job.volumeName, job.image, job.env);

  const stopStreamer = startLogStreamer(
    job.containerName,
    job.workerJobId,
    job.logCallbackUrl,
    job.ephemeralToken
  );

  // Stop log streamer once the container exits (monitorContainer handles exit)
  // We wrap monitorContainer to stop streaming after exit
  const proc = spawn('docker', ['wait', job.containerName], { stdio: ['ignore', 'pipe', 'pipe'] });

  let output = '';
  proc.stdout.on('data', (d: Buffer) => { output += d.toString(); });

  proc.on('close', async () => {
    stopStreamer();

    const exitCode = parseInt(output.trim(), 10);
    const code = isNaN(exitCode) ? 1 : exitCode;

    if (code === 0) {
      await handleSuccessExit(job);
    } else {
      await handleFailureExit(job, code);
    }
  });
}

export interface StoppedContainer {
  name: string;
  runId: string;
  jobId: string;
}

export function scanStoppedContainers(): StoppedContainer[] {
  const result = spawnSync('docker', [
    'ps', '-a',
    '--filter', 'name=conductor-',
    '--format', '{{.Names}}\t{{.Status}}',
  ], { encoding: 'utf-8' });

  if (result.status !== 0 || !result.stdout) {
    return [];
  }

  const stopped: StoppedContainer[] = [];
  for (const line of result.stdout.trim().split('\n')) {
    if (!line.trim()) continue;
    const [name, status] = line.split('\t');
    if (!name || !status) continue;

    const isRunning = status.toLowerCase().startsWith('up');
    if (isRunning) continue;

    // Parse conductor-{runId}-{jobId}
    const match = name.match(/^conductor-([^-]+)-(.+)$/);
    if (!match) continue;

    stopped.push({ name, runId: match[1], jobId: match[2] });
  }

  return stopped;
}
