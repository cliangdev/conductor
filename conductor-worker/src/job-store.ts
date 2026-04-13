export type JobStatus = 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';

export interface Job {
  workerJobId: string;
  runId: string;
  jobId: string;
  image: string;
  env: Record<string, string>;
  logCallbackUrl: string;
  outputsCallbackUrl: string;
  jobFailedCallbackUrl: string;
  ephemeralToken: string;
  containerName: string;
  volumeName: string;
  status: JobStatus;
  exitCode?: number;
}

const jobs = new Map<string, Job>();

export function addJob(job: Job): void {
  jobs.set(job.workerJobId, job);
}

export function getJob(workerJobId: string): Job | undefined {
  return jobs.get(workerJobId);
}

export function updateJobStatus(
  workerJobId: string,
  status: JobStatus,
  exitCode?: number
): void {
  const job = jobs.get(workerJobId);
  if (job) {
    job.status = status;
    if (exitCode !== undefined) {
      job.exitCode = exitCode;
    }
  }
}

export function removeJob(workerJobId: string): boolean {
  return jobs.delete(workerJobId);
}

export function countRunningJobs(): number {
  let count = 0;
  for (const job of jobs.values()) {
    if (job.status === 'RUNNING') count++;
  }
  return count;
}

export function getAllJobs(): Job[] {
  return Array.from(jobs.values());
}

export function clearAll(): void {
  jobs.clear();
}
