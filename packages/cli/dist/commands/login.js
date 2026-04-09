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
exports.registerLogin = registerLogin;
const http = __importStar(require("http"));
const net = __importStar(require("net"));
const chalk_1 = __importDefault(require("chalk"));
const ora_1 = __importDefault(require("ora"));
const config_js_1 = require("../lib/config.js");
const CONDUCTOR_API_URL = process.env['CONDUCTOR_API_URL'] ?? 'http://localhost:8080';
const PORT_MIN = 3131;
const PORT_MAX = 3199;
const LOGIN_TIMEOUT_MS = 120000;
async function findAvailablePort() {
    for (let port = PORT_MIN; port <= PORT_MAX; port++) {
        const available = await isPortAvailable(port);
        if (available)
            return port;
    }
    throw new Error(`No available port found in range ${PORT_MIN}-${PORT_MAX}`);
}
function isPortAvailable(port) {
    return new Promise((resolve) => {
        const server = net.createServer();
        server.once('error', () => resolve(false));
        server.once('listening', () => {
            server.close(() => resolve(true));
        });
        server.listen(port);
    });
}
function startCallbackServer(port, onCallback, onError) {
    const server = http.createServer((req, res) => {
        const url = new URL(req.url ?? '/', `http://localhost:${port}`);
        if (url.pathname !== '/oauth/callback') {
            res.writeHead(404);
            res.end('Not found');
            return;
        }
        const apiKey = url.searchParams.get('apiKey');
        const projectId = url.searchParams.get('projectId');
        const projectName = url.searchParams.get('projectName');
        const email = url.searchParams.get('email');
        if (!apiKey || !projectId || !projectName || !email) {
            res.writeHead(400);
            res.end('Missing required parameters');
            onError(new Error('OAuth callback missing required parameters'));
            return;
        }
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end('<html><body><h1>Authentication successful!</h1><p>You can close this tab and return to the terminal.</p></body></html>');
        onCallback({ apiKey, projectId, projectName, email });
    });
    server.listen(port);
    return server;
}
function registerLogin(program) {
    program
        .command('login')
        .description('Authenticate with Conductor via browser')
        .option('--force', 'Re-authenticate even if already logged in')
        .action(async (options) => {
        const existing = (0, config_js_1.readConfig)();
        if (existing && !options.force) {
            console.log(`Already logged in as ${existing.email}. Use --force to re-authenticate.`);
            process.exit(0);
            return;
        }
        let port;
        try {
            port = await findAvailablePort();
        }
        catch (err) {
            console.error(chalk_1.default.red(err.message));
            process.exit(1);
            return;
        }
        const spinner = (0, ora_1.default)('Opening browser for authentication...').start();
        const callbackPromise = new Promise((resolve, reject) => {
            const server = startCallbackServer(port, (params) => {
                server.close();
                resolve({
                    ...params,
                    apiUrl: CONDUCTOR_API_URL,
                });
            }, (err) => {
                server.close();
                reject(err);
            });
            const timeout = setTimeout(() => {
                server.close();
                reject(new Error('Authentication timed out after 120 seconds'));
            }, LOGIN_TIMEOUT_MS);
            server.on('close', () => clearTimeout(timeout));
            process.on('SIGINT', () => {
                server.close();
                clearTimeout(timeout);
                spinner.fail('Authentication cancelled');
                process.exit(1);
            });
        });
        try {
            const { default: open } = await Promise.resolve().then(() => __importStar(require('open')));
            const loginUrl = `${CONDUCTOR_API_URL}/auth/cli-login?port=${port}`;
            await open(loginUrl);
            const config = await callbackPromise;
            (0, config_js_1.writeConfig)(config);
            spinner.succeed(chalk_1.default.green(`Logged in as ${config.email} (project: ${config.projectName})`));
            process.exit(0);
        }
        catch (err) {
            spinner.fail(chalk_1.default.red(err.message));
            process.exit(1);
        }
    });
}
//# sourceMappingURL=login.js.map