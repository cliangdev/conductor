import type { Config } from './config.js';
export declare function apiGet<T>(urlPath: string, config: Config): Promise<T>;
export declare function apiPost<T>(urlPath: string, body: unknown, config: Config): Promise<T>;
export declare function apiPatch<T>(urlPath: string, body: unknown, config: Config): Promise<T>;
export declare function apiPut<T>(urlPath: string, body: unknown, config: Config): Promise<T>;
export declare function apiDelete(urlPath: string, config: Config): Promise<void>;
//# sourceMappingURL=api.d.ts.map