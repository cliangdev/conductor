import { apiPost, apiPut, apiDelete } from '../api.js';
import { writeDocumentFile, deleteDocumentFile } from '../files.js';
export async function createDocument(params, config) {
    writeDocumentFile(config.projectId, params.issueId, params.filename, params.content);
    const result = await apiPost(`/api/v1/projects/${config.projectId}/issues/${params.issueId}/documents`, { filename: params.filename, content: params.content, contentType: 'text/markdown' }, config);
    return {
        documentId: result.id,
        filename: result.filename,
        issueId: params.issueId,
    };
}
export async function updateDocument(params, config) {
    const result = await apiPut(`/api/v1/projects/${config.projectId}/issues/${params.issueId}/documents/${params.documentId}`, { content: params.content }, config);
    if (result.filename) {
        writeDocumentFile(config.projectId, params.issueId, result.filename, params.content);
    }
    return {
        documentId: params.documentId,
        issueId: params.issueId,
        filename: result.filename,
    };
}
export async function deleteDocument(params, config) {
    deleteDocumentFile(config.projectId, params.issueId, params.filename);
    await apiDelete(`/api/v1/projects/${config.projectId}/issues/${params.issueId}/documents/${params.documentId}`, config);
    return { success: true };
}
//# sourceMappingURL=documents.js.map