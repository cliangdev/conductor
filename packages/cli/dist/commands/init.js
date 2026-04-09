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
exports.getIssuesDir = getIssuesDir;
exports.readMcpJson = readMcpJson;
exports.writeMcpJson = writeMcpJson;
exports.buildMcpJson = buildMcpJson;
exports.registerInit = registerInit;
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const os = __importStar(require("os"));
const chalk_1 = __importDefault(require("chalk"));
const config_js_1 = require("../lib/config.js");
const CONDUCTOR_MCP_ENTRY = {
    command: 'npx',
    args: ['@conductor/mcp'],
};
function getIssuesDir(projectId) {
    return path.join(os.homedir(), '.conductor', projectId, 'issues');
}
function readMcpJson(workingDir) {
    const mcpPath = path.join(workingDir, '.mcp.json');
    try {
        const raw = fs.readFileSync(mcpPath, 'utf8');
        return JSON.parse(raw);
    }
    catch {
        return {};
    }
}
function writeMcpJson(workingDir, content) {
    const mcpPath = path.join(workingDir, '.mcp.json');
    fs.writeFileSync(mcpPath, JSON.stringify(content, null, 2) + '\n', 'utf8');
}
function buildMcpJson(existing) {
    return {
        ...existing,
        mcpServers: {
            ...(existing.mcpServers ?? {}),
            conductor: CONDUCTOR_MCP_ENTRY,
        },
    };
}
function registerInit(program) {
    program
        .command('init')
        .description('Initialize Conductor in the current project')
        .option('--path <dir>', 'Working directory to initialize', process.cwd())
        .action((options) => {
        const config = (0, config_js_1.readConfig)();
        if (!config) {
            console.error('Please run `conductor login` first');
            process.exit(1);
            return;
        }
        const issuesDir = getIssuesDir(config.projectId);
        fs.mkdirSync(issuesDir, { recursive: true });
        const workingDir = path.resolve(options.path);
        const existing = readMcpJson(workingDir);
        const updated = buildMcpJson(existing);
        writeMcpJson(workingDir, updated);
        const relativeIssuesDir = issuesDir.replace(os.homedir(), '~');
        console.log(chalk_1.default.green(`✓ Local directory: ${relativeIssuesDir}`));
        console.log(chalk_1.default.green('✓ .mcp.json updated'));
    });
}
//# sourceMappingURL=init.js.map