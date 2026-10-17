import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';

/**
 * A multi-line secret (a `.p8` private key): a monospace textarea that is never echoed back after
 * save — the form always starts empty and the backend never returns the value. Used by the generic
 * connector config fields (FieldType SECRET_TEXT) and Apple Search Ads' own connect form.
 */
export function SecretTextField({
  label,
  hint,
  required,
  value,
  onChange,
  id,
}: {
  label: string;
  hint?: string | null;
  required?: boolean;
  value: string;
  onChange: (text: string) => void;
  id: string;
}) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <Textarea
        id={id}
        rows={5}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={'-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----'}
        required={required}
        autoComplete="off"
        autoCorrect="off"
        autoCapitalize="off"
        spellCheck={false}
        className="font-mono text-xs"
      />
      <p className="mt-1 text-xs text-muted-foreground">{hint ? `${hint.replace(/\.$/, "")}. ` : ""}Stored encrypted; never shown again.</p>
    </div>
  );
}
