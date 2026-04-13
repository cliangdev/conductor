import request from 'supertest';
import express from 'express';
import { bearerAuth } from '../auth';

function makeApp(secret: string | undefined) {
  const app = express();
  if (secret !== undefined) {
    process.env.CONDUCTOR_WORKER_SECRET = secret;
  } else {
    delete process.env.CONDUCTOR_WORKER_SECRET;
  }
  app.get('/protected', bearerAuth, (_req, res) => {
    res.json({ ok: true });
  });
  return app;
}

afterEach(() => {
  delete process.env.CONDUCTOR_WORKER_SECRET;
});

describe('bearerAuth middleware', () => {
  it('returns 401 when Authorization header is missing', async () => {
    const app = makeApp('mysecret');
    const res = await request(app).get('/protected');
    expect(res.status).toBe(401);
  });

  it('returns 401 when token is wrong', async () => {
    const app = makeApp('mysecret');
    const res = await request(app)
      .get('/protected')
      .set('Authorization', 'Bearer wrongtoken');
    expect(res.status).toBe(401);
  });

  it('returns 401 when Authorization is not Bearer scheme', async () => {
    const app = makeApp('mysecret');
    const res = await request(app)
      .get('/protected')
      .set('Authorization', 'Basic mysecret');
    expect(res.status).toBe(401);
  });

  it('passes through when token matches secret', async () => {
    const app = makeApp('mysecret');
    const res = await request(app)
      .get('/protected')
      .set('Authorization', 'Bearer mysecret');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ ok: true });
  });

  it('returns 500 when CONDUCTOR_WORKER_SECRET is not set', async () => {
    const app = makeApp(undefined);
    const res = await request(app)
      .get('/protected')
      .set('Authorization', 'Bearer anything');
    expect(res.status).toBe(500);
  });
});
