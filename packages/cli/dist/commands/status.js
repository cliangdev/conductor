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
exports.isDaemonRunning = isDaemonRunning;
exports.getQueueCount = getQueueCount;
exports.registerStatus = registerStatus;
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const os = __importStar(require("os"));
const chalk_1 = __importDefault(require("chalk"));
const config_js_1 = require("../lib/config.js");
const DAEMON_PID_PATH = path.join(os.homedir(), '.conductor', 'daemon.pid');
const SYNC_QUEUE_PATH = path.join(os.homedir(), '.conductor', 'sync-queue.json');
function isDaemonRunning() {
    try {
        const raw = fs.readFileSync(DAEMON_PID_PATH, 'utf8').trim();
        const pid = parseInt(raw, 10);
        if (isNaN(pid))
            return false;
        process.kill(pid, 0);
        return true;
    }
    catch {
        return false;
    }
}
function getQueueCount() {
    try {
        const raw = fs.readFileSync(SYNC_QUEUE_PATH, 'utf8');
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed))
            return parsed.length;
        return 0;
    }
    catch {
        return 0;
    }
}
function registerStatus(program) {
    program
        .command('status')
        .description('Show current Conductor status')
        .action(() => {
        const config = (0, config_js_1.readConfig)();
        if (!config) {
            console.log(`Auth:      ${chalk_1.default.red('✗')} Not authenticated — run conductor login`);
            return;
        }
        const daemonRunning = isDaemonRunning();
        const queueCount = getQueueCount();
        console.log(`Auth:      ${chalk_1.default.green('✓')} Logged in as ${config.email}`);
        console.log(`Project:   ${config.projectName} (${config.projectId})`);
        console.log(`Daemon:    ${daemonRunning ? chalk_1.default.green('✓ Running') : chalk_1.default.red('✗ Not running')}`);
        console.log(`API URL:   ${config.apiUrl}`);
        console.log(`Queue:     ${queueCount} pending changes`);
    });
}
//# sourceMappingURL=status.js.map