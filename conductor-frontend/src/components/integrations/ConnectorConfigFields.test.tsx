import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ConnectorConfigFields } from './ConnectorConfigFields'
import { buildConnectionPayload } from '@/lib/connectorConnectForm'
import type { ConnectorConfigField } from '@/lib/api'

const fields: ConnectorConfigField[] = [
  { key: 'issuerId', label: 'Issuer ID', hint: null, type: 'STRING', source: 'USER_INPUT', required: true, secret: false },
  {
    key: 'apiKey',
    label: 'Private Key (.p8)',
    hint: 'Contents of the .p8 file',
    type: 'SECRET_TEXT',
    source: 'USER_INPUT',
    required: true,
    secret: true,
  },
]

describe('ConnectorConfigFields SECRET_TEXT', () => {
  it('renders a multi-line monospace textarea that reports edits', () => {
    const setFormValues = vi.fn()
    render(
      <ConnectorConfigFields
        fields={fields}
        formValues={{}}
        setFormValues={setFormValues}
        jsonFieldErrors={{}}
        applyJsonField={vi.fn()}
      />,
    )
    const box = screen.getByLabelText('Private Key (.p8)')
    expect(box.tagName).toBe('TEXTAREA')
    expect(box).toHaveClass('font-mono')
    expect(box).toHaveValue('')
    fireEvent.change(box, { target: { value: '-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----' } })
    expect(setFormValues).toHaveBeenCalledTimes(1)
    expect(screen.getByText(/Stored encrypted; never shown again/)).toBeInTheDocument()
  })

  it('sends the first secret as the apiKey and keeps the rest as config', () => {
    const result = buildConnectionPayload(
      { configFields: fields, authType: 'API_KEY' },
      { issuerId: 'iss-1', apiKey: 'KEY\nLINES' },
    )
    expect(result).toEqual({ ok: true, body: { apiKey: 'KEY\nLINES', configJson: { issuerId: 'iss-1' } } })
  })
})
