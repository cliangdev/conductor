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
export declare function addJob(job: Job): void;
export declare function getJob(workerJobId: string): Job | undefined;
export declare function updateJobStatus(workerJobId: string, status: JobStatus, exitCode?: number): void;
export declare function removeJob(workerJobId: string): boolean;
export declare function countRunningJobs(): number;
export declare function getAllJobs(): Job[];
export declare function clearAll(): void;
//# sourceMappingURL=job-store.d.ts.map