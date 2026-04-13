"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.buildContainerName = buildContainerName;
exports.buildVolumeName = buildVolumeName;
exports.createVolume = createVolume;
exports.startContainer = startContainer;
exports.monitorContainer = monitorContainer;
exports.cleanupContainer = cleanupContainer;
exports.launchJob = launchJob;
exports.scanStoppedContainers = scanStoppedContainers;
const child_process_1 = require("child_process");
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const axios_1 = __importDefault(require("axios"));
const job_store_1 = require("./job-store");
const log_streamer_1 = require("./log-streamer");
function buildContainerName(runId, jobId) {
    return `conductor-${runId}-${jobId}`;
}
function buildVolumeName(runId, jobId) {
    return `conductor-vol-${runId}-${jobId}`;
}
function runCommand(cmd, args) {
    return new Promise((resolve) => {
        const proc = (0, child_process_1.spawn)(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'] });
        let stdout = '';
        let stderr = '';
        proc.stdout.on('data', (d) => { stdout += d.toString(); });
        proc.stderr.on('data', (d) => { stderr += d.toString(); });
        proc.on('close', (code) => {
            resolve({ exitCode: code ?? 1, stdout, stderr });
        });
    });
}
async function createVolume(volumeName) {
    const result = await runCommand('docker', ['volume', 'create', volumeName]);
    if (result.exitCode !== 0) {
        throw new Error(`Failed to create volume ${volumeName}: ${result.stderr}`);
    }
}
async function startContainer(containerName, volumeName, image, env) {
    const envArgs = [];
    for (const [key, value] of Object.entries(env)) {
        envArgs.push('-e', `${key}=${value}`);
    }
    const result = await runCommand('docker', [
        'run', '-d',
        '--name', containerName,
        '-v', `${volumeName}:/conductor/workspace`,
        '--rm=false',
        ...envArgs,
        image,
    ]);
    if (result.exitCode !== 0) {
        throw new Error(`Failed to start container ${containerName}: ${result.stderr}`);
    }
}
function monitorContainer(job) {
    const proc = (0, child_process_1.spawn)('docker', ['wait', job.containerName], { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    proc.stdout.on('data', (d) => { output += d.toString(); });
    proc.on('close', async () => {
        const exitCode = parseInt(output.trim(), 10);
        const code = isNaN(exitCode) ? 1 : exitCode;
        if (code === 0) {
            await handleSuccessExit(job);
        }
        else {
            await handleFailureExit(job, code);
        }
    });
}
async function collectOutputs(job) {
    const outputDir = `/tmp/outputs-${job.workerJobId}`;
    fs.mkdirSync(outputDir, { recursive: true });
    const copyResult = await runCommand('docker', [
        'cp',
        `${job.containerName}:/conductor/outputs/.`,
        outputDir,
    ]);
    if (copyResult.exitCode !== 0) {
        return {};
    }
    const outputs = {};
    try {
        const files = fs.readdirSync(outputDir);
        for (const file of files) {
            try {
                const filePath = path.join(outputDir, file);
                const stat = fs.statSync(filePath);
                if (stat.isFile()) {
                    outputs[file] = fs.readFileSync(filePath, 'utf-8');
                }
            }
            catch {
                outputs[file] = null;
            }
        }
    }
    catch {
        // outputs directory may not exist in container; that's fine
    }
    try {
        fs.rmSync(outputDir, { recursive: true, force: true });
    }
    catch {
        // cleanup is best-effort
    }
    return outputs;
}
async function handleSuccessExit(job) {
    (0, job_store_1.updateJobStatus)(job.workerJobId, 'SUCCESS', 0);
    const outputs = await collectOutputs(job);
    try {
        await axios_1.default.post(job.outputsCallbackUrl, { workerJobId: job.workerJobId, outputs }, {
            headers: { Authorization: `Bearer ${job.ephemeralToken}` },
            timeout: 10000,
        });
    }
    catch {
        // callback failure is non-fatal for cleanup
    }
    await cleanupContainer(job.containerName);
    (0, job_store_1.removeJob)(job.workerJobId);
}
async function handleFailureExit(job, exitCode) {
    (0, job_store_1.updateJobStatus)(job.workerJobId, 'FAILED', exitCode);
    try {
        await axios_1.default.post(job.jobFailedCallbackUrl, {
            workerJobId: job.workerJobId,
            exitCode,
            reason: `Container exited with code ${exitCode}`,
        }, {
            headers: { Authorization: `Bearer ${job.ephemeralToken}` },
            timeout: 10000,
        });
    }
    catch {
        // callback failure is non-fatal for cleanup
    }
    await cleanupContainer(job.containerName);
    (0, job_store_1.removeJob)(job.workerJobId);
}
async function cleanupContainer(containerName) {
    await runCommand('docker', ['rm', '-v', containerName]);
}
async function launchJob(job) {
    await createVolume(job.volumeName);
    await startContainer(job.containerName, job.volumeName, job.image, job.env);
    const stopStreamer = (0, log_streamer_1.startLogStreamer)(job.containerName, job.workerJobId, job.logCallbackUrl, job.ephemeralToken);
    // Stop log streamer once the container exits (monitorContainer handles exit)
    // We wrap monitorContainer to stop streaming after exit
    const proc = (0, child_process_1.spawn)('docker', ['wait', job.containerName], { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    proc.stdout.on('data', (d) => { output += d.toString(); });
    proc.on('close', async () => {
        stopStreamer();
        const exitCode = parseInt(output.trim(), 10);
        const code = isNaN(exitCode) ? 1 : exitCode;
        if (code === 0) {
            await handleSuccessExit(job);
        }
        else {
            await handleFailureExit(job, code);
        }
    });
}
function scanStoppedContainers() {
    const result = (0, child_process_1.spawnSync)('docker', [
        'ps', '-a',
        '--filter', 'name=conductor-',
        '--format', '{{.Names}}\t{{.Status}}',
    ], { encoding: 'utf-8' });
    if (result.status !== 0 || !result.stdout) {
        return [];
    }
    const stopped = [];
    for (const line of result.stdout.trim().split('\n')) {
        if (!line.trim())
            continue;
        const [name, status] = line.split('\t');
        if (!name || !status)
            continue;
        const isRunning = status.toLowerCase().startsWith('up');
        if (isRunning)
            continue;
        // Parse conductor-{runId}-{jobId}
        const match = name.match(/^conductor-([^-]+)-(.+)$/);
        if (!match)
            continue;
        stopped.push({ name, runId: match[1], jobId: match[2] });
    }
    return stopped;
}
//# sourceMappingURL=docker.js.map