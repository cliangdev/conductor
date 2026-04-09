async function request(method, urlPath, body, config) {
    const url = `${config.apiUrl}${urlPath}`;
    const headers = {
        Authorization: `Bearer ${config.apiKey}`,
        'Content-Type': 'application/json',
    };
    const response = await fetch(url, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new Error(`API error ${response.status}: ${text}`);
    }
    if (method === 'DELETE') {
        return undefined;
    }
    return response.json();
}
export async function apiGet(urlPath, config) {
    return request('GET', urlPath, undefined, config);
}
export async function apiPost(urlPath, body, config) {
    return request('POST', urlPath, body, config);
}
export async function apiPatch(urlPath, body, config) {
    return request('PATCH', urlPath, body, config);
}
export async function apiPut(urlPath, body, config) {
    return request('PUT', urlPath, body, config);
}
export async function apiDelete(urlPath, config) {
    await request('DELETE', urlPath, undefined, config);
}
//# sourceMappingURL=api.js.map