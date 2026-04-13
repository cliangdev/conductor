import request from 'supertest';

const WORKER_SECRET = 'test-secret';

beforeEach(() => {
  process.env.CONDUCTOR_WORKER_SECRET = WORKER_SECRET;
  process.env.CONDUCTOR_MAX_CONCURRENT_JOBS = '5';
  jest.resetModules();
});

afterEach(() => {
  delete process.env.CONDUCTOR_WORKER_SECRET;
  delete process.env.CONDUCTOR_MAX_CONCURRENT_JOBS;
});

function authHeader() {
  return { Authorization: `Bearer ${WORKER_SECRET}` };
}

async function getApp() {
  // Mock docker module to avoid real docker calls
  jest.mock('../docker', () => ({
    buildContainerName: (runId: string, jobId: string) => `conductor-${runId}-${jobId}`,
    buildVolumeName: (runId: string, jobId: string) => `conductor-vol-${runId}-${jobId}`,
    launchJob: jest.fn().mockResolvedValue(undefined),
    cleanupContainer: jest.fn().mockResolvedValue(undefined),
  }));
  jest.mock('../startup', () => ({
    recoverStoppedContainers: jest.fn().mockResolvedValue(undefined),
  }));

  const { clearAll } = await import('../job-store');
  clearAll();

  const { app } = await import('../index');
  return app;
}

const validRunJobBody = {
  runId: 'run-abc',
  jobId: 'job-123',
  image: 'my-image:latest',
  env: { FOO: 'bar' },
  logCallbackUrl: 'http://backend/log',
  outputsCallbackUrl: 'http://backend/outputs',
  jobFailedCallbackUrl: 'http://backend/failed',
  ephemeralToken: 'tok-xyz',
};

describe('POST /run-job', () => {
  it('returns 401 without auth', async () => {
    const app = await getApp();
    const res = await request(app).post('/run-job').send(validRunJobBody);
    expect(res.status).toBe(401);
  });

  it('returns 401 with wrong secret', async () => {
    const app = await getApp();
    const res = await request(app)
      .post('/run-job')
      .set('Authorization', 'Bearer wrong')
      .send(validRunJobBody);
    expect(res.status).toBe(401);
  });

  it('returns 202 with workerJobId on valid request', async () => {
    const app = await getApp();
    const res = await request(app)
      .post('/run-job')
      .set(authHeader())
      .send(validRunJobBody);
    expect(res.status).toBe(202);
    expect(res.body.workerJobId).toBeDefined();
    expect(typeof res.body.workerJobId).toBe('string');
  });

  it('returns 400 when required fields are missing', async () => {
    const app = await getApp();
    const res = await request(app)
      .post('/run-job')
      .set(authHeader())
      .send({ runId: 'run-abc' });
    expect(res.status).toBe(400);
  });

  it('returns 503 when at capacity', async () => {
    process.env.CONDUCTOR_MAX_CONCURRENT_JOBS = '2';
    jest.resetModules();

    jest.mock('../docker', () => ({
      buildContainerName: (runId: string, jobId: string) => `conductor-${runId}-${jobId}`,
      buildVolumeName: (runId: string, jobId: string) => `conductor-vol-${runId}-${jobId}`,
      launchJob: jest.fn().mockReturnValue(new Promise(() => {})), // never resolves
      cleanupContainer: jest.fn().mockResolvedValue(undefined),
    }));
    jest.mock('../startup', () => ({
      recoverStoppedContainers: jest.fn().mockResolvedValue(undefined),
    }));

    const { clearAll } = await import('../job-store');
    clearAll();
    const { app } = await import('../index');

    await request(app).post('/run-job').set(authHeader()).send({ ...validRunJobBody, jobId: 'job-1' });
    await request(app).post('/run-job').set(authHeader()).send({ ...validRunJobBody, jobId: 'job-2' });
    const res = await request(app).post('/run-job').set(authHeader()).send({ ...validRunJobBody, jobId: 'job-3' });
    expect(res.status).toBe(503);
  });
});

describe('GET /job/:workerJobId/status', () => {
  it('returns 401 without auth', async () => {
    const app = await getApp();
    const res = await request(app).get('/job/some-id/status');
    expect(res.status).toBe(401);
  });

  it('returns 404 for unknown job', async () => {
    const app = await getApp();
    const res = await request(app)
      .get('/job/nonexistent/status')
      .set(authHeader());
    expect(res.status).toBe(404);
  });

  it('returns job status for existing job', async () => {
    const app = await getApp();
    const createRes = await request(app)
      .post('/run-job')
      .set(authHeader())
      .send(validRunJobBody);
    const { workerJobId } = createRes.body;

    const statusRes = await request(app)
      .get(`/job/${workerJobId}/status`)
      .set(authHeader());
    expect(statusRes.status).toBe(200);
    expect(statusRes.body.workerJobId).toBe(workerJobId);
    expect(statusRes.body.status).toBe('RUNNING');
  });
});

describe('DELETE /job/:workerJobId', () => {
  it('returns 401 without auth', async () => {
    const app = await getApp();
    const res = await request(app).delete('/job/some-id');
    expect(res.status).toBe(401);
  });

  it('returns 404 for unknown job', async () => {
    const app = await getApp();
    const res = await request(app)
      .delete('/job/nonexistent')
      .set(authHeader());
    expect(res.status).toBe(404);
  });

  it('returns 200 and cancels existing job', async () => {
    const app = await getApp();
    const createRes = await request(app)
      .post('/run-job')
      .set(authHeader())
      .send(validRunJobBody);
    const { workerJobId } = createRes.body;

    const deleteRes = await request(app)
      .delete(`/job/${workerJobId}`)
      .set(authHeader());
    expect(deleteRes.status).toBe(200);
    expect(deleteRes.body.status).toBe('CANCELLED');
  });
});
