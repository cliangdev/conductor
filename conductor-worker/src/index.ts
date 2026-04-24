import express, { Request, Response } from 'express';
import { v4 as uuidv4 } from 'uuid';
import { bearerAuth } from './auth';
import { addJob, getJob, updateJobStatus, countRunningJobs } from './job-store';
import { buildContainerName, buildVolumeName, launchJob, cleanupContainer } from './docker';
import { recoverStoppedContainers } from './startup';

const app = express();
app.use(express.json());

const MAX_CONCURRENT_JOBS = parseInt(process.env.CONDUCTOR_MAX_CONCURRENT_JOBS ?? '5', 10);
const PORT = parseInt(process.env.PORT ?? '3001', 10);

app.post('/run-job', bearerAuth, async (req: Request, res: Response) => {
  const {
    runId,
    jobId,
    image,
    env,
    logCallbackUrl,
    outputsCallbackUrl,
    jobFailedCallbackUrl,
    ephemeralToken,
  } = req.body;

  if (!runId || !jobId || !image || !logCallbackUrl || !outputsCallbackUrl || !jobFailedCallbackUrl || !ephemeralToken) {
    res.status(400).json({ error: 'Missing required fields' });
    return;
  }

  if (countRunningJobs() >= MAX_CONCURRENT_JOBS) {
    res.status(503).json({ error: 'Worker at capacity; try again later' });
    return;
  }

  const workerJobId = uuidv4();
  const containerName = buildContainerName(runId, jobId);
  const volumeName = buildVolumeName(runId, jobId);

  const job = {
    workerJobId,
    runId,
    jobId,
    image,
    env: env ?? {},
    logCallbackUrl,
    outputsCallbackUrl,
    jobFailedCallbackUrl,
    ephemeralToken,
    containerName,
    volumeName,
    status: 'RUNNING' as const,
  };

  addJob(job);
  res.status(202).json({ workerJobId });

  launchJob(job).catch((err) => {
    console.error(`Failed to launch job ${workerJobId}:`, err);
    updateJobStatus(workerJobId, 'FAILED');
  });
});

app.get('/job/:workerJobId/status', bearerAuth, (req: Request, res: Response) => {
  const job = getJob(req.params.workerJobId as string);
  if (!job) {
    res.status(404).json({ error: 'Job not found' });
    return;
  }
  const response: { workerJobId: string; status: string; exitCode?: number } = {
    workerJobId: job.workerJobId,
    status: job.status,
  };
  if (job.exitCode !== undefined) {
    response.exitCode = job.exitCode;
  }
  res.json(response);
});

app.delete('/job/:workerJobId', bearerAuth, async (req: Request, res: Response) => {
  const job = getJob(req.params.workerJobId as string);
  if (!job) {
    res.status(404).json({ error: 'Job not found' });
    return;
  }

  updateJobStatus(job.workerJobId, 'CANCELLED');

  cleanupContainer(job.containerName).catch((err) => {
    console.error(`Cleanup failed for ${job.containerName}:`, err);
  });

  res.status(200).json({ workerJobId: job.workerJobId, status: 'CANCELLED' });
});

app.get('/health', (_req: Request, res: Response) => {
  res.json({ status: 'ok' });
});

export { app };

if (require.main === module) {
  recoverStoppedContainers()
    .catch((err) => console.error('Crash recovery failed:', err))
    .finally(() => {
      app.listen(PORT, () => {
        console.log(`conductor-worker listening on port ${PORT}`);
      });
    });
}
