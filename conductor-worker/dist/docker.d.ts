import { Job } from './job-store';
export declare function buildContainerName(runId: string, jobId: string): string;
export declare function buildVolumeName(runId: string, jobId: string): string;
export declare function createVolume(volumeName: string): Promise<void>;
export declare function startContainer(containerName: string, volumeName: string, image: string, env: Record<string, string>): Promise<void>;
export declare function monitorContainer(job: Job): void;
export declare function cleanupContainer(containerName: string): Promise<void>;
export declare function launchJob(job: Job): Promise<void>;
export interface StoppedContainer {
    name: string;
    runId: string;
    jobId: string;
}
export declare function scanStoppedContainers(): StoppedContainer[];
//# sourceMappingURL=docker.d.ts.map