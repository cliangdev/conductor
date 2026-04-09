import { Config } from '../config.js';
export declare function createDocument(params: {
    issueId: string;
    filename: string;
    content: string;
}, config: Config): Promise<Record<string, unknown>>;
export declare function updateDocument(params: {
    issueId: string;
    documentId: string;
    content: string;
}, config: Config): Promise<Record<string, unknown>>;
export declare function deleteDocument(params: {
    issueId: string;
    documentId: string;
    filename: string;
}, config: Config): Promise<Record<string, unknown>>;
//# sourceMappingURL=documents.d.ts.map