export interface Config {
    apiKey: string;
    projectId: string;
    projectName: string;
    email: string;
    apiUrl: string;
}
export declare const CONFIG_PATH: string;
export declare function readConfig(): Config | null;
export declare function writeConfig(config: Config): void;
export declare function clearConfig(): void;
//# sourceMappingURL=config.d.ts.map