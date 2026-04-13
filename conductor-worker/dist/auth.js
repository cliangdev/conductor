"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.bearerAuth = bearerAuth;
function bearerAuth(req, res, next) {
    const secret = process.env.CONDUCTOR_WORKER_SECRET;
    if (!secret) {
        res.status(500).json({ error: 'Server misconfigured: CONDUCTOR_WORKER_SECRET not set' });
        return;
    }
    const authHeader = req.headers['authorization'];
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        res.status(401).json({ error: 'Missing or invalid Authorization header' });
        return;
    }
    const token = authHeader.slice('Bearer '.length);
    if (token !== secret) {
        res.status(401).json({ error: 'Invalid token' });
        return;
    }
    next();
}
//# sourceMappingURL=auth.js.map