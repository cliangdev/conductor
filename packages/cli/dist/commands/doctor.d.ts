import { Command } from 'commander';
export declare function checkApiHealth(apiUrl: string): Promise<boolean>;
export declare function checkMcpJson(workingDir: string): boolean;
export declare function checkIssuesDir(projectId: string): boolean;
export declare function checkConfigFile(): boolean;
export declare function registerDoctor(program: Command): void;
//# sourceMappingURL=doctor.d.ts.map