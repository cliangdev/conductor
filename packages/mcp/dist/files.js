import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
function conductorDir() {
    return path.join(os.homedir(), '.conductor');
}
function issueFilePath(projectId, issueId) {
    return path.join(conductorDir(), projectId, 'issues', issueId, 'issue.md');
}
function documentFilePath(projectId, issueId, filename) {
    return path.join(conductorDir(), projectId, 'issues', issueId, filename);
}
export function writeIssueFile(projectId, issueId, content) {
    const filePath = issueFilePath(projectId, issueId);
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, content, 'utf8');
}
export function readIssueFile(projectId, issueId) {
    const filePath = issueFilePath(projectId, issueId);
    try {
        return fs.readFileSync(filePath, 'utf8');
    }
    catch {
        return null;
    }
}
export function writeDocumentFile(projectId, issueId, filename, content) {
    const filePath = documentFilePath(projectId, issueId, filename);
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, content, 'utf8');
}
export function deleteDocumentFile(projectId, issueId, filename) {
    const filePath = documentFilePath(projectId, issueId, filename);
    try {
        fs.unlinkSync(filePath);
    }
    catch {
        // File may not exist; that's fine
    }
}
//# sourceMappingURL=files.js.map