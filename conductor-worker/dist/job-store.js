"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.addJob = addJob;
exports.getJob = getJob;
exports.updateJobStatus = updateJobStatus;
exports.removeJob = removeJob;
exports.countRunningJobs = countRunningJobs;
exports.getAllJobs = getAllJobs;
exports.clearAll = clearAll;
const jobs = new Map();
function addJob(job) {
    jobs.set(job.workerJobId, job);
}
function getJob(workerJobId) {
    return jobs.get(workerJobId);
}
function updateJobStatus(workerJobId, status, exitCode) {
    const job = jobs.get(workerJobId);
    if (job) {
        job.status = status;
        if (exitCode !== undefined) {
            job.exitCode = exitCode;
        }
    }
}
function removeJob(workerJobId) {
    return jobs.delete(workerJobId);
}
function countRunningJobs() {
    let count = 0;
    for (const job of jobs.values()) {
        if (job.status === 'RUNNING')
            count++;
    }
    return count;
}
function getAllJobs() {
    return Array.from(jobs.values());
}
function clearAll() {
    jobs.clear();
}
//# sourceMappingURL=job-store.js.map