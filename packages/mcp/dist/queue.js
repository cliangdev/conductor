import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
const QUEUE_PATH = path.join(os.homedir(), '.conductor', 'sync-queue.json');
export function queueChange(change) {
    let queue = [];
    try {
        const raw = fs.readFileSync(QUEUE_PATH, 'utf8');
        queue = JSON.parse(raw);
    }
    catch {
        queue = [];
    }
    queue.push(change);
    fs.mkdirSync(path.dirname(QUEUE_PATH), { recursive: true });
    fs.writeFileSync(QUEUE_PATH, JSON.stringify(queue, null, 2), 'utf8');
    return queue.length;
}
//# sourceMappingURL=queue.js.map