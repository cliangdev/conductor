import axios from 'axios';
import { scanStoppedContainers, cleanupContainer } from './docker';

export async function recoverStoppedContainers(): Promise<void> {
  const backendUrl = process.env.CONDUCTOR_BACKEND_URL;
  const workerSecret = process.env.CONDUCTOR_WORKER_SECRET;

  const stopped = scanStoppedContainers();

  if (stopped.length === 0) return;

  console.log(`Crash recovery: found ${stopped.length} stopped conductor container(s)`);

  for (const container of stopped) {
    console.log(`Recovering container: ${container.name}`);

    try {
      await cleanupContainer(container.name);
    } catch (err) {
      console.error(`Failed to remove container ${container.name}:`, err);
    }

    if (backendUrl && workerSecret) {
      try {
        await axios.post(
          `${backendUrl}/internal/workflow-runs/${container.runId}/job-failed`,
          {
            jobId: container.jobId,
            reason: 'Worker restarted; container lost',
          },
          {
            headers: { Authorization: `Bearer ${workerSecret}` },
            timeout: 10000,
          }
        );
      } catch (err) {
        console.error(`Failed to POST failure callback for ${container.name}:`, err);
      }
    } else {
      console.warn('CONDUCTOR_BACKEND_URL or CONDUCTOR_WORKER_SECRET not set; skipping failure callback');
    }
  }
}
