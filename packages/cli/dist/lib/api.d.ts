export declare class ApiError extends Error {
    readonly statusCode: number;
    constructor(message: string, statusCode: number);
}
export declare function apiGet<T>(path: string, apiKey: string, apiUrl: string): Promise<T>;
export declare function apiPost<T>(path: string, body: unknown, apiKey: string, apiUrl: string): Promise<T>;
//# sourceMappingURL=api.d.ts.map