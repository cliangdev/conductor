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
Object.defineProperty(exports, "__esModule", { value: true });
exports.CONFIG_PATH = void 0;
exports.readConfig = readConfig;
exports.writeConfig = writeConfig;
exports.clearConfig = clearConfig;
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const os = __importStar(require("os"));
exports.CONFIG_PATH = path.join(os.homedir(), '.conductor', 'config.json');
function readConfig() {
    try {
        const raw = fs.readFileSync(exports.CONFIG_PATH, 'utf8');
        const parsed = JSON.parse(raw);
        if (!isConfig(parsed))
            return null;
        return parsed;
    }
    catch {
        return null;
    }
}
function writeConfig(config) {
    const dir = path.dirname(exports.CONFIG_PATH);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(exports.CONFIG_PATH, JSON.stringify(config, null, 2), 'utf8');
}
function clearConfig() {
    try {
        fs.unlinkSync(exports.CONFIG_PATH);
    }
    catch {
        // File may not exist; that's fine
    }
}
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
//# sourceMappingURL=config.js.map