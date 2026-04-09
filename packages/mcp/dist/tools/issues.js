import { apiGet, apiPost, apiPatch } from '../api.js';
import { writeIssueFile, readIssueFile } from '../files.js';
import { queueChange } from '../queue.js';
function buildIssueFrontmatter(issueId, type, title, status, description) {
    const body = description ?? '';
    return `---\nid: ${issueId}\ntype: ${type}\ntitle: ${title}\nstatus: ${status}\n---\n\n${body}`;
}
function updateFrontmatterField(content, field, value) {
    const pattern = new RegExp(`^(${field}:\\s*)(.*)$`, 'm');
    if (pattern.test(content)) {
        return content.replace(pattern, `$1${value}`);
    }
    return content;
}
export async function createIssue(params, config) {
    let issueId;
    let backendResult = null;
    let warning;
    let queueSize;
    try {
        backendResult = await apiPost(`/api/v1/projects/${config.projectId}/issues`, { type: params.type, title: params.title, description: params.description }, config);
        issueId = backendResult.id;
    }
    catch {
        issueId = `local_${Date.now()}`;
        const size = queueChange({
            method: 'POST',
            path: `/api/v1/projects/${config.projectId}/issues`,
            body: { type: params.type, title: params.title, description: params.description },
            timestamp: new Date().toISOString(),
        });
        warning = 'Sync failed — change queued';
        queueSize = size;
    }
    const content = buildIssueFrontmatter(issueId, params.type, params.title, 'DRAFT', params.description);
    writeIssueFile(config.projectId, issueId, content);
    const result = {
        issueId,
        type: params.type,
        title: params.title,
        status: 'DRAFT',
    };
    if (warning !== undefined) {
        result['warning'] = warning;
        result['queueSize'] = queueSize;
    }
    return result;
}
export async function updateIssue(params, config) {
    const body = {};
    if (params.title !== undefined)
        body['title'] = params.title;
    if (params.description !== undefined)
        body['description'] = params.description;
    let warning;
    let queueSize;
    try {
        await apiPatch(`/api/v1/projects/${config.projectId}/issues/${params.issueId}`, body, config);
    }
    catch {
        const size = queueChange({
            method: 'PATCH',
            path: `/api/v1/projects/${config.projectId}/issues/${params.issueId}`,
            body,
            timestamp: new Date().toISOString(),
        });
        warning = 'Sync failed — change queued';
        queueSize = size;
    }
    const existing = readIssueFile(config.projectId, params.issueId);
    if (existing !== null) {
        let updated = existing;
        if (params.title !== undefined) {
            updated = updateFrontmatterField(updated, 'title', params.title);
        }
        writeIssueFile(config.projectId, params.issueId, updated);
    }
    const result = { issueId: params.issueId, ...body };
    if (warning !== undefined) {
        result['warning'] = warning;
        result['queueSize'] = queueSize;
    }
    return result;
}
export async function setIssueStatus(params, config) {
    let warning;
    let queueSize;
    try {
        await apiPatch(`/api/v1/projects/${config.projectId}/issues/${params.issueId}`, { status: params.status }, config);
    }
    catch {
        const size = queueChange({
            method: 'PATCH',
            path: `/api/v1/projects/${config.projectId}/issues/${params.issueId}`,
            body: { status: params.status },
            timestamp: new Date().toISOString(),
        });
        warning = 'Sync failed — change queued';
        queueSize = size;
    }
    const existing = readIssueFile(config.projectId, params.issueId);
    if (existing !== null) {
        const updated = updateFrontmatterField(existing, 'status', params.status);
        writeIssueFile(config.projectId, params.issueId, updated);
    }
    const result = {
        issueId: params.issueId,
        status: params.status,
    };
    if (warning !== undefined) {
        result['warning'] = warning;
        result['queueSize'] = queueSize;
    }
    return result;
}
export async function listIssues(params, config) {
    const query = new URLSearchParams();
    if (params.type)
        query.set('type', params.type);
    if (params.status)
        query.set('status', params.status);
    const qs = query.toString();
    const path = `/api/v1/projects/${config.projectId}/issues${qs ? `?${qs}` : ''}`;
    return apiGet(path, config);
}
export async function getIssue(params, config) {
    const local = readIssueFile(config.projectId, params.issueId);
    if (local !== null) {
        return { issueId: params.issueId, content: local, source: 'local' };
    }
    const issue = await apiGet(`/api/v1/projects/${config.projectId}/issues/${params.issueId}`, config);
    return issue;
}
//# sourceMappingURL=issues.js.map