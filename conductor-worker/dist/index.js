"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.app = void 0;
const express_1 = __importDefault(require("express"));
const uuid_1 = require("uuid");
const auth_1 = require("./auth");
const job_store_1 = require("./job-store");
const docker_1 = require("./docker");
const startup_1 = require("./startup");
const app = (0, express_1.default)();
exports.app = app;
app.use(express_1.default.json());
const MAX_CONCURRENT_JOBS = parseInt(process.env.CONDUCTOR_MAX_CONCURRENT_JOBS ?? '5', 10);
const PORT = parseInt(process.env.PORT ?? '3001', 10);
app.post('/run-job', auth_1.bearerAuth, async (req, res) => {
    const { runId, jobId, image, env, logCallbackUrl, outputsCallbackUrl, jobFailedCallbackUrl, ephemeralToken, } = req.body;
    if (!runId || !jobId || !image || !logCallbackUrl || !outputsCallbackUrl || !jobFailedCallbackUrl || !ephemeralToken) {
        res.status(400).json({ error: 'Missing required fields' });
        return;
    }
    if ((0, job_store_1.countRunningJobs)() >= MAX_CONCURRENT_JOBS) {
        res.status(503).json({ error: 'Worker at capacity; try again later' });
        return;
    }
    const workerJobId = (0, uuid_1.v4)();
    const containerName = (0, docker_1.buildContainerName)(runId, jobId);
    const volumeName = (0, docker_1.buildVolumeName)(runId, jobId);
    const job = {
        workerJobId,
        runId,
        jobId,
        image,
        env: env ?? {},
        logCallbackUrl,
        outputsCallbackUrl,
        jobFailedCallbackUrl,
        ephemeralToken,
        containerName,
        volumeName,
        status: 'RUNNING',
    };
    (0, job_store_1.addJob)(job);
    res.status(202).json({ workerJobId });
    (0, docker_1.launchJob)(job).catch((err) => {
        console.error(`Failed to launch job ${workerJobId}:`, err);
        (0, job_store_1.updateJobStatus)(workerJobId, 'FAILED');
    });
});
app.get('/job/:workerJobId/status', auth_1.bearerAuth, (req, res) => {
    const job = (0, job_store_1.getJob)(req.params.workerJobId);
    if (!job) {
        res.status(404).json({ error: 'Job not found' });
        return;
    }
    const response = {
        workerJobId: job.workerJobId,
        status: job.status,
    };
    if (job.exitCode !== undefined) {
        response.exitCode = job.exitCode;
    }
    res.json(response);
});
app.delete('/job/:workerJobId', auth_1.bearerAuth, async (req, res) => {
    const job = (0, job_store_1.getJob)(req.params.workerJobId);
    if (!job) {
        res.status(404).json({ error: 'Job not found' });
        return;
    }
    (0, job_store_1.updateJobStatus)(job.workerJobId, 'CANCELLED');
    (0, docker_1.cleanupContainer)(job.containerName).catch((err) => {
        console.error(`Cleanup failed for ${job.containerName}:`, err);
    });
    res.status(200).json({ workerJobId: job.workerJobId, status: 'CANCELLED' });
});
app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
});
if (require.main === module) {
    (0, startup_1.recoverStoppedContainers)()
        .catch((err) => console.error('Crash recovery failed:', err))
        .finally(() => {
        app.listen(PORT, () => {
            console.log(`conductor-worker listening on port ${PORT}`);
        });
    });
}
//# sourceMappingURL=index.js.map