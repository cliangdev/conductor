package com.conductor.entity;

/**
 * A row that owns envelope-encrypted secrets: it carries its own AES-256 data encryption key (DEK),
 * wrapped by the deployment's key encryption key (KEK) and stored Base64 in the row's
 * {@code kms_key_reference} column. That DEK — and nothing else — encrypts every secret on the row.
 *
 * <p>This is the whole of what {@code CredentialService}'s envelope needs from the thing it
 * encrypts for, which is why it is a two-method interface rather than an entity type. A new table
 * joins the existing envelope by implementing it and adding a {@code kms_key_reference} column;
 * copying the AES/GCM + KEK-wrapping code into a second class instead is how two implementations
 * drift apart, and crypto that has drifted is a vulnerability.
 *
 * @see Connection
 * @see ConnectorAppCredential
 */
public interface EnvelopeEncrypted {

    /** The row's DEK, wrapped by the KEK and Base64-encoded; null until the first secret is stored. */
    String getKmsKeyReference();

    void setKmsKeyReference(String kmsKeyReference);
}
