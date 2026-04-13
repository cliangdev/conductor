import { Request, Response, NextFunction } from 'express';

export function bearerAuth(req: Request, res: Response, next: NextFunction): void {
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
