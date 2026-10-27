package com.conductor.service;

import com.conductor.entity.Connection;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.kms.v1.DecryptResponse;
import com.google.cloud.kms.v1.EncryptResponse;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GcpKmsCredentialServiceTest {

    private KeyManagementServiceClient kms;
    private AtomicLong nanos;
    private GcpKmsCredentialService service;

    @BeforeEach
    void setUp() {
        kms = mock(KeyManagementServiceClient.class);
        nanos = new AtomicLong();
        service = new GcpKmsCredentialService(new ObjectMapper(), kms, "p", "global", "ring", "key", nanos::get);
        // "Wrapping" reverses nothing: the wrapped blob is the plaintext prefixed with "w", so blobs stay distinct.
        when(kms.encrypt(anyString(), any(ByteString.class))).thenAnswer(inv -> {
            ByteString plain = inv.getArgument(1);
            return EncryptResponse.newBuilder().setCiphertext(ByteString.copyFromUtf8("w").concat(plain)).build();
        });
        when(kms.decrypt(anyString(), any(ByteString.class))).thenAnswer(inv -> {
            ByteString wrapped = inv.getArgument(1);
            return DecryptResponse.newBuilder().setPlaintext(wrapped.substring(1)).build();
        });
    }

    private Connection connectionWithSecret(String secret) {
        Connection c = new Connection();
        service.putWebhookSecret(c, secret);
        return c;
    }

    @Test
    void secondDecryptOfTheSameBlobDoesNotCallKms() {
        Connection c = connectionWithSecret("s3cret");

        assertThat(service.decryptWebhookSecret(c)).isEqualTo("s3cret");
        assertThat(service.decryptWebhookSecret(c)).isEqualTo("s3cret");
        assertThat(service.decryptWebhookSecret(c)).isEqualTo("s3cret");

        verify(kms, times(1)).decrypt(anyString(), any(ByteString.class));
    }

    @Test
    void anEntryExpiresAfterTwentyMinutes() {
        Connection c = connectionWithSecret("s3cret");
        service.decryptWebhookSecret(c);

        nanos.addAndGet(java.time.Duration.ofMinutes(19).toNanos());
        service.decryptWebhookSecret(c);
        verify(kms, times(1)).decrypt(anyString(), any(ByteString.class));

        nanos.addAndGet(java.time.Duration.ofMinutes(2).toNanos());
        assertThat(service.decryptWebhookSecret(c)).isEqualTo("s3cret");
        verify(kms, times(2)).decrypt(anyString(), any(ByteString.class));
    }

    @Test
    void differentWrappedBlobsDoNotCollide() {
        Connection a = connectionWithSecret("alpha");
        Connection b = connectionWithSecret("beta");
        assertThat(a.getKmsKeyReference()).isNotEqualTo(b.getKmsKeyReference());

        assertThat(service.decryptWebhookSecret(a)).isEqualTo("alpha");
        assertThat(service.decryptWebhookSecret(b)).isEqualTo("beta");
        assertThat(service.decryptWebhookSecret(a)).isEqualTo("alpha");
        assertThat(service.decryptWebhookSecret(b)).isEqualTo("beta");

        verify(kms, times(2)).decrypt(anyString(), any(ByteString.class));
    }

    @Test
    void aBlankReferenceNeverTouchesKms() {
        assertThat(service.decryptWebhookSecret(new Connection())).isNull();
        verify(kms, times(0)).decrypt(anyString(), any(ByteString.class));
    }
}
