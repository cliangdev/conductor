"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.startLogStreamer = startLogStreamer;
const child_process_1 = require("child_process");
const axios_1 = __importDefault(require("axios"));
const FLUSH_INTERVAL_MS = 500;
const FLUSH_LINE_COUNT = 10;
function startLogStreamer(containerName, workerJobId, logCallbackUrl, ephemeralToken) {
    const buffer = [];
    let flushTimer = null;
    let stopped = false;
    async function flush() {
        if (buffer.length === 0)
            return;
        const lines = buffer.splice(0, buffer.length);
        try {
            await axios_1.default.post(logCallbackUrl, {
                workerJobId,
                lines,
                timestamp: new Date().toISOString(),
            }, {
                headers: { Authorization: `Bearer ${ephemeralToken}` },
                timeout: 5000,
            });
        }
        catch {
            // Log callback failures are non-fatal; continue streaming
        }
    }
    flushTimer = setInterval(() => {
        flush().catch(() => { });
    }, FLUSH_INTERVAL_MS);
    const proc = (0, child_process_1.spawn)('docker', ['logs', '-f', containerName], {
        stdio: ['ignore', 'pipe', 'pipe'],
    });
    function handleLine(line) {
        if (stopped)
            return;
        buffer.push(line);
        if (buffer.length >= FLUSH_LINE_COUNT) {
            flush().catch(() => { });
        }
    }
    let stdoutRemainder = '';
    proc.stdout.on('data', (chunk) => {
        const text = stdoutRemainder + chunk.toString();
        const lines = text.split('\n');
        stdoutRemainder = lines.pop() ?? '';
        for (const line of lines) {
            handleLine(line);
        }
    });
    let stderrRemainder = '';
    proc.stderr.on('data', (chunk) => {
        const text = stderrRemainder + chunk.toString();
        const lines = text.split('\n');
        stderrRemainder = lines.pop() ?? '';
        for (const line of lines) {
            handleLine(line);
        }
    });
    proc.on('close', () => {
        if (stdoutRemainder)
            handleLine(stdoutRemainder);
        if (stderrRemainder)
            handleLine(stderrRemainder);
        flush().catch(() => { });
    });
    return function stop() {
        stopped = true;
        if (flushTimer) {
            clearInterval(flushTimer);
            flushTimer = null;
        }
        proc.kill();
        flush().catch(() => { });
    };
}
//# sourceMappingURL=log-streamer.js.map