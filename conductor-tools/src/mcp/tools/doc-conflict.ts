/**
 * A 409 on a document write that carried `baseContentHash` means someone changed the document since the
 * caller read it. Re-raise it with the instruction an agent needs; any other error passes through.
 * Duck-typed on `status` so it works for any error from the API client.
 */
export function conflictAsClearError(err: unknown): unknown {
  if (err instanceof Error && (err as { status?: number }).status === 409) {
    return new Error(
      'Document changed since you read it — read it again, redo your edit on the new content, then write once more.'
    )
  }
  return err
}
