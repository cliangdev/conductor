"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.registerLogout = registerLogout;
const config_js_1 = require("../lib/config.js");
function registerLogout(program) {
    program
        .command('logout')
        .description('Clear saved Conductor credentials')
        .action(() => {
        (0, config_js_1.clearConfig)();
        console.log('Logged out.');
    });
}
//# sourceMappingURL=logout.js.map