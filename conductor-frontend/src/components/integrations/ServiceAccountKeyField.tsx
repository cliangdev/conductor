'use client';

/**
 * Textarea + "Upload .json" input for a service-account JSON key. Shared by the integrations hub's
 * generic JSON field renderer and GcpConnectorPage's connect form so the two never diverge.
 * Validation lives with the caller (it owns the form state); this component only renders the
 * value/error it is given. The file is read client-side into the textarea value — never uploaded.
 */
export function ServiceAccountKeyField({
  label,
  hint,
  required,
  value,
  error,
  onChange,
}: {
  label: string;
  hint?: string | null;
  required?: boolean;
  value: string;
  error?: string | null;
  onChange: (text: string) => void;
}) {
  const handleFile = async (file: File | undefined) => {
    if (!file) return;
    onChange(await file.text());
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-1">
        <label className="block text-sm font-medium text-foreground">{label}</label>
        <label className="text-xs font-medium text-primary hover:underline cursor-pointer">
          Upload .json
          <input
            type="file"
            accept=".json,application/json"
            className="hidden"
            onChange={(e) => handleFile(e.target.files?.[0])}
          />
        </label>
      </div>
      <textarea
        rows={6}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={hint || ''}
        required={required}
        className="w-full rounded-md border border-input bg-background px-3 py-2 text-xs font-mono placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
      />
      {error && <p className="mt-1 text-xs text-destructive">{error}</p>}
    </div>
  );
}
