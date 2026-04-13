"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.recoverStoppedContainers = recoverStoppedContainers;
const axios_1 = __importDefault(require("axios"));
const docker_1 = require("./docker");
async function recoverStoppedContainers() {
    const backendUrl = process.env.CONDUCTOR_BACKEND_URL;
    const workerSecret = process.env.CONDUCTOR_WORKER_SECRET;
    const stopped = (0, docker_1.scanStoppedContainers)();
    if (stopped.length === 0)
        return;
    console.log(`Crash recovery: found ${stopped.length} stopped conductor container(s)`);
    for (const container of stopped) {
        console.log(`Recovering container: ${container.name}`);
        try {
            await (0, docker_1.cleanupContainer)(container.name);
        }
        catch (err) {
            console.error(`Failed to remove container ${container.name}:`, err);
        }
        if (backendUrl && workerSecret) {
            try {
                await axios_1.default.post(`${backendUrl}/internal/workflow-runs/${container.runId}/job-failed`, {
                    jobId: container.jobId,
                    reason: 'Worker restarted; container lost',
                }, {
                    headers: { Authorization: `Bearer ${workerSecret}` },
                    timeout: 10000,
                });
            }
            catch (err) {
                console.error(`Failed to POST failure callback for ${container.name}:`, err);
            }
        }
        else {
            console.warn('CONDUCTOR_BACKEND_URL or CONDUCTOR_WORKER_SECRET not set; skipping failure callback');
        }
    }
}
//# sourceMappingURL=startup.js.map