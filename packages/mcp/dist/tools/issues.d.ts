import { Config } from '../config.js';
export declare function createIssue(params: {
    type: string;
    title: string;
    description?: string;
}, config: Config): Promise<Record<string, unknown>>;
export declare function updateIssue(params: {
    issueId: string;
    title?: string;
    description?: string;
}, config: Config): Promise<Record<string, unknown>>;
export declare function setIssueStatus(params: {
    issueId: string;
    status: string;
}, config: Config): Promise<Record<string, unknown>>;
export declare function listIssues(params: {
    type?: string;
    status?: string;
}, config: Config): Promise<unknown[]>;
export declare function getIssue(params: {
    issueId: string;
}, config: Config): Promise<Record<string, unknown>>;
//# sourceMappingURL=issues.d.ts.map