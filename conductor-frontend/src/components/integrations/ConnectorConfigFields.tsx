import { ServiceAccountKeyField } from './ServiceAccountKeyField';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select } from '@/components/ui/select';
import type { ConnectorConfigField } from '@/lib/api';

/**
 * Renders a connector's USER_INPUT config fields (secret/select/text) — shared by the integrations
 * hub's connect modal and any connector's own detail-page connect form, so the field-type switch
 * lives in one place.
 */
export function ConnectorConfigFields({
  fields,
  formValues,
  setFormValues,
  jsonFieldErrors,
  applyJsonField,
}: {
  fields: ConnectorConfigField[];
  formValues: Record<string, string>;
  setFormValues: (update: (prev: Record<string, string>) => Record<string, string>) => void;
  jsonFieldErrors: Record<string, string>;
  applyJsonField: (key: string, value: string) => void;
}) {
  return (
    <>
      {fields
        .filter((field) => field.source === 'USER_INPUT')
        .map((field) => (
          <div key={field.key}>
            {field.type === 'JSON' ? (
              <ServiceAccountKeyField
                label={field.label}
                hint={field.hint}
                required={field.required}
                value={formValues[field.key] || ''}
                error={jsonFieldErrors[field.key] || null}
                onChange={(text) => applyJsonField(field.key, text)}
              />
            ) : field.type === 'SELECT' ? (
              <>
                <Label>{field.label}</Label>
                {/* No connector currently ships enumerated options for the backend to send;
                    this is a structural placeholder ready for that data. */}
                <Select
                  value={formValues[field.key] || ''}
                  onChange={(e) => setFormValues((prev) => ({ ...prev, [field.key]: e.target.value }))}
                  required={field.required}
                >
                  <option value="" disabled>
                    {field.hint || 'Select…'}
                  </option>
                </Select>
              </>
            ) : (
              <>
                <Label>{field.label}</Label>
                <Input
                  type={field.secret ? 'password' : 'text'}
                  value={formValues[field.key] || ''}
                  onChange={(e) => setFormValues((prev) => ({ ...prev, [field.key]: e.target.value }))}
                  placeholder={field.hint || ''}
                  required={field.required}
                />
              </>
            )}
          </div>
        ))}
    </>
  );
}
