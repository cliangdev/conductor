import axios from 'axios';

jest.mock('axios');
const mockedAxios = axios as jest.Mocked<typeof axios>;

jest.mock('../docker', () => ({
  scanStoppedContainers: jest.fn(),
  cleanupContainer: jest.fn().mockResolvedValue(undefined),
}));

import { scanStoppedContainers, cleanupContainer } from '../docker';
const mockedScan = scanStoppedContainers as jest.Mock;
const mockedCleanup = cleanupContainer as jest.Mock;

import { recoverStoppedContainers } from '../startup';

beforeEach(() => {
  jest.clearAllMocks();
  process.env.CONDUCTOR_BACKEND_URL = 'http://backend';
  process.env.CONDUCTOR_WORKER_SECRET = 'worker-secret';
});

afterEach(() => {
  delete process.env.CONDUCTOR_BACKEND_URL;
  delete process.env.CONDUCTOR_WORKER_SECRET;
});

describe('recoverStoppedContainers', () => {
  it('does nothing when no stopped containers', async () => {
    mockedScan.mockReturnValue([]);
    await recoverStoppedContainers();
    expect(mockedCleanup).not.toHaveBeenCalled();
    expect(mockedAxios.post).not.toHaveBeenCalled();
  });

  it('cleans up stopped containers and posts failure callback', async () => {
    mockedScan.mockReturnValue([
      { name: 'conductor-run1-job1', runId: 'run1', jobId: 'job1' },
    ]);
    mockedAxios.post.mockResolvedValue({ status: 200 });

    await recoverStoppedContainers();

    expect(mockedCleanup).toHaveBeenCalledWith('conductor-run1-job1');
    expect(mockedAxios.post).toHaveBeenCalledWith(
      'http://backend/internal/workflow-runs/run1/job-failed',
      { jobId: 'job1', reason: 'Worker restarted; container lost' },
      expect.objectContaining({
        headers: { Authorization: 'Bearer worker-secret' },
      })
    );
  });

  it('handles multiple stopped containers', async () => {
    mockedScan.mockReturnValue([
      { name: 'conductor-run1-job1', runId: 'run1', jobId: 'job1' },
      { name: 'conductor-run2-job2', runId: 'run2', jobId: 'job2' },
    ]);
    mockedAxios.post.mockResolvedValue({ status: 200 });

    await recoverStoppedContainers();

    expect(mockedCleanup).toHaveBeenCalledTimes(2);
    expect(mockedAxios.post).toHaveBeenCalledTimes(2);
  });

  it('continues recovering other containers when one cleanup fails', async () => {
    mockedScan.mockReturnValue([
      { name: 'conductor-run1-job1', runId: 'run1', jobId: 'job1' },
      { name: 'conductor-run2-job2', runId: 'run2', jobId: 'job2' },
    ]);
    mockedCleanup
      .mockRejectedValueOnce(new Error('docker rm failed'))
      .mockResolvedValueOnce(undefined);
    mockedAxios.post.mockResolvedValue({ status: 200 });

    await recoverStoppedContainers();

    expect(mockedCleanup).toHaveBeenCalledTimes(2);
  });

  it('skips callback when CONDUCTOR_BACKEND_URL is not set', async () => {
    delete process.env.CONDUCTOR_BACKEND_URL;
    mockedScan.mockReturnValue([
      { name: 'conductor-run1-job1', runId: 'run1', jobId: 'job1' },
    ]);

    await recoverStoppedContainers();

    expect(mockedCleanup).toHaveBeenCalledWith('conductor-run1-job1');
    expect(mockedAxios.post).not.toHaveBeenCalled();
  });

  it('still cleans up when failure callback POST fails', async () => {
    mockedScan.mockReturnValue([
      { name: 'conductor-run1-job1', runId: 'run1', jobId: 'job1' },
    ]);
    mockedAxios.post.mockRejectedValue(new Error('network error'));

    await expect(recoverStoppedContainers()).resolves.not.toThrow();
    expect(mockedCleanup).toHaveBeenCalledWith('conductor-run1-job1');
  });
});
