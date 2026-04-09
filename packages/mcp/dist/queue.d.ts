export interface QueuedChange {
    method: string;
    path: string;
    body?: unknown;
    timestamp: string;
}
export declare function queueChange(change: QueuedChange): number;
//# sourceMappingURL=queue.d.ts.map