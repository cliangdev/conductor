"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ApiError = void 0;
exports.apiGet = apiGet;
exports.apiPost = apiPost;
class ApiError extends Error {
    constructor(message, statusCode) {
        super(message);
        this.statusCode = statusCode;
        this.name = 'ApiError';
    }
}
exports.ApiError = ApiError;
async function apiGet(path, apiKey, apiUrl) {
    const url = `${apiUrl}${path}`;
    const response = await fetch(url, {
        method: 'GET',
        headers: {
            'Authorization': `Bearer ${apiKey}`,
            'Content-Type': 'application/json',
        },
    });
    if (!response.ok) {
        const text = await response.text().catch(() => response.statusText);
        throw new ApiError(`GET ${path} failed with status ${response.status}: ${text}`, response.status);
    }
    return response.json();
}
async function apiPost(path, body, apiKey, apiUrl) {
    const url = `${apiUrl}${path}`;
    const response = await fetch(url, {
        method: 'POST',
        headers: {
            'Authorization': `Bearer ${apiKey}`,
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
    });
    if (!response.ok) {
        const text = await response.text().catch(() => response.statusText);
        throw new ApiError(`POST ${path} failed with status ${response.status}: ${text}`, response.status);
    }
    return response.json();
}
//# sourceMappingURL=api.js.map