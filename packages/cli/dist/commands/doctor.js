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
exports.checkApiHealth = checkApiHealth;
exports.checkMcpJson = checkMcpJson;
exports.checkIssuesDir = checkIssuesDir;
exports.checkConfigFile = checkConfigFile;
exports.registerDoctor = registerDoctor;
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const chalk_1 = __importDefault(require("chalk"));
const config_js_1 = require("../lib/config.js");
const init_js_1 = require("./init.js");
async function checkApiHealth(apiUrl) {
    try {
        const response = await fetch(`${apiUrl}/api/v1/health`);
        return response.ok;
    }
    catch {
        return false;
    }
}
function checkMcpJson(workingDir) {
    return fs.existsSync(path.join(workingDir, '.mcp.json'));
}
function checkIssuesDir(projectId) {
    return fs.existsSync((0, init_js_1.getIssuesDir)(projectId));
}
function checkConfigFile() {
    return fs.existsSync(config_js_1.CONFIG_PATH);
}
function registerDoctor(program) {
    program
        .command('doctor')
        .description('Run health checks on your Conductor setup')
        .action(async () => {
        const configExists = checkConfigFile();
        const configMark = configExists ? chalk_1.default.green('✓') : chalk_1.default.red('✗');
        console.log(`${configMark} Config file found (~/.conductor/config.json)`);
        const config = (0, config_js_1.readConfig)();
        if (config) {
            const apiReachable = await checkApiHealth(config.apiUrl);
            const apiMark = apiReachable ? chalk_1.default.green('✓') : chalk_1.default.red('✗');
            console.log(`${apiMark} API reachable (GET /api/v1/health → ${apiReachable ? '200' : 'failed'})`);
        }
        else {
            console.log(`${chalk_1.default.red('✗')} API reachable (no config — skipped)`);
        }
        const mcpExists = checkMcpJson(process.cwd());
        const mcpMark = mcpExists ? chalk_1.default.green('✓') : chalk_1.default.red('✗');
        console.log(`${mcpMark} .mcp.json ${mcpExists ? 'found' : 'not found'} in current directory`);
        if (config) {
            const issuesDirExists = checkIssuesDir(config.projectId);
            const issuesMark = issuesDirExists ? chalk_1.default.green('✓') : chalk_1.default.red('✗');
            console.log(`${issuesMark} Local issues directory ${issuesDirExists ? 'exists' : 'not found'}`);
        }
        else {
            console.log(`${chalk_1.default.red('✗')} Local issues directory (no config — skipped)`);
        }
    });
}
//# sourceMappingURL=doctor.js.map