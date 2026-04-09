import { Command } from 'commander';
interface McpServerEntry {
    command: string;
    args?: string[];
    env?: Record<string, string>;
}
interface McpJson {
    mcpServers?: Record<string, McpServerEntry>;
    [key: string]: unknown;
}
export declare function getIssuesDir(projectId: string): string;
export declare function readMcpJson(workingDir: string): McpJson;
export declare function writeMcpJson(workingDir: string, content: McpJson): void;
export declare function buildMcpJson(existing: McpJson): McpJson;
export declare function registerInit(program: Command): void;
export {};
//# sourceMappingURL=init.d.ts.map