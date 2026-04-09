#!/usr/bin/env node
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const commander_1 = require("commander");
const login_js_1 = require("./commands/login.js");
const logout_js_1 = require("./commands/logout.js");
const init_js_1 = require("./commands/init.js");
const status_js_1 = require("./commands/status.js");
const doctor_js_1 = require("./commands/doctor.js");
const program = new commander_1.Command();
program
    .name('conductor')
    .description('Conductor CLI for project setup and issue management')
    .version('0.1.0');
(0, login_js_1.registerLogin)(program);
(0, logout_js_1.registerLogout)(program);
(0, init_js_1.registerInit)(program);
(0, status_js_1.registerStatus)(program);
(0, doctor_js_1.registerDoctor)(program);
program.parse();
//# sourceMappingURL=index.js.map