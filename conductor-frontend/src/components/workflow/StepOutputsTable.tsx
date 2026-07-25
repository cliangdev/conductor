'use client';

const MAX_OUTPUT_VALUE_DISPLAY = 400;

function formatOutputValue(value: unknown): string {
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

export function StepOutputsTable({ outputs }: { outputs: Record<string, unknown> }) {
  return (
    <div>
      <p className="text-xs font-medium text-muted-foreground mb-1">Outputs</p>
      <table className="w-full text-xs border rounded overflow-hidden">
        <thead className="bg-muted/50">
          <tr>
            <th className="text-left p-2 font-medium">Key</th>
            <th className="text-left p-2 font-medium">Value</th>
          </tr>
        </thead>
        <tbody>
          {Object.entries(outputs).map(([key, value]) => {
            const display = formatOutputValue(value);
            const isLong = display.length > MAX_OUTPUT_VALUE_DISPLAY;
            return (
              <tr key={key} className="border-t">
                <td className="p-2 font-mono">{key}</td>
                <td className="p-2 font-mono break-all">
                  {isLong ? (
                    <details>
                      <summary className="cursor-pointer text-muted-foreground">
                        {display.slice(0, MAX_OUTPUT_VALUE_DISPLAY)}…
                      </summary>
                      <div className="mt-1 whitespace-pre-wrap">{display}</div>
                    </details>
                  ) : (
                    display
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
