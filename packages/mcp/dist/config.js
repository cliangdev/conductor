import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
export const CONFIG_PATH = path.join(os.homedir(), '.conductor', 'config.json');
function isConfig(value) {
    if (typeof value !== 'object' || value === null)
        return false;
    const obj = value;
    return (typeof obj['apiKey'] === 'string' &&
        typeof obj['projectId'] === 'string' &&
        typeof obj['projectName'] === 'string' &&
        typeof obj['email'] === 'string' &&
        typeof obj['apiUrl'] === 'string');
}
export function getConfig() {
    try {
        const raw = fs.readFileSync(CONFIG_PATH, 'utf8');
        const parsed = JSON.parse(raw);
        if (!isConfig(parsed)) {
            throw new Error('Invalid config: missing required fields');
        }
        return parsed;
    }
    catch (err) {
        if (err instanceof Error && err.message.startsWith('Invalid config')) {
            throw err;
        }
        throw new Error('Config not found — run conductor login');
    }
}
//# sourceMappingURL=config.js.map