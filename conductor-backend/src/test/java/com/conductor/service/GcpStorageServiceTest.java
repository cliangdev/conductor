package com.conductor.service;

import com.conductor.exception.StorageUploadException;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GcpStorageServiceTest {

    @Mock
    private Storage storage;

    private GcpStorageService gcpStorageService;

    private static final String BUCKET_NAME = "test-bucket";

    @BeforeEach
    void setUp() {
        gcpStorageService = new GcpStorageService(storage, BUCKET_NAME);
    }

    @Test
    void upload_callsStorageCreateWithCorrectBlobInfo() {
        String gcsPath = "uploads/project-1/file.pdf";
        byte[] content = "pdf-content".getBytes();
        String contentType = "application/pdf";

        gcpStorageService.upload(gcsPath, content, contentType);

        ArgumentCaptor<BlobInfo> blobInfoCaptor = ArgumentCaptor.forClass(BlobInfo.class);
        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(storage).create(blobInfoCaptor.capture(), contentCaptor.capture());

        BlobInfo capturedBlobInfo = blobInfoCaptor.getValue();
        assertThat(capturedBlobInfo.getBucket()).isEqualTo(BUCKET_NAME);
        assertThat(capturedBlobInfo.getName()).isEqualTo(gcsPath);
        assertThat(capturedBlobInfo.getContentType()).isEqualTo(contentType);
        assertThat(contentCaptor.getValue()).isEqualTo(content);
    }

    @Test
    void delete_whenStorageThrows_doesNotPropagateException() {
        String gcsPath = "uploads/project-1/file.pdf";
        doThrow(new RuntimeException("GCS error")).when(storage).delete(any(BlobId.class));

        assertThatCode(() -> gcpStorageService.delete(gcsPath))
                .doesNotThrowAnyException();
    }

    @Test
    void delete_callsStorageDeleteWithCorrectBlobId() {
        String gcsPath = "uploads/project-1/file.pdf";

        gcpStorageService.delete(gcsPath);

        ArgumentCaptor<BlobId> blobIdCaptor = ArgumentCaptor.forClass(BlobId.class);
        verify(storage).delete(blobIdCaptor.capture());

        BlobId capturedBlobId = blobIdCaptor.getValue();
        assertThat(capturedBlobId.getBucket()).isEqualTo(BUCKET_NAME);
        assertThat(capturedBlobId.getName()).isEqualTo(gcsPath);
    }

    @Test
    void generateSignedUrl_returnsV4SignedUrl() throws MalformedURLException {
        String gcsPath = "uploads/project-1/file.pdf";
        int expiryMinutes = 15;
        URL expectedUrl = new URL("https://storage.googleapis.com/test-bucket/uploads/project-1/file.pdf?X-Goog-Signature=abc123");

        when(storage.signUrl(
                any(BlobInfo.class),
                eq((long) expiryMinutes),
                eq(TimeUnit.MINUTES),
                any(Storage.SignUrlOption.class)
        )).thenReturn(expectedUrl);

        String result = gcpStorageService.generateSignedUrl(gcsPath, expiryMinutes);

        assertThat(result).isEqualTo(expectedUrl.toString());
    }

    @Test
    void upload_retriesOnTransientFailureAndSucceedsOnThirdAttempt() {
        String gcsPath = "uploads/project-1/file.pdf";
        byte[] content = "pdf-content".getBytes();
        String contentType = "application/pdf";

        doThrow(new RuntimeException("transient error"))
                .doThrow(new RuntimeException("transient error again"))
                .doReturn(null)
                .when(storage).create(any(BlobInfo.class), any(byte[].class));

        gcpStorageService.retryDelays = new int[]{0, 0, 0};

        assertThatCode(() -> gcpStorageService.upload(gcsPath, content, contentType))
                .doesNotThrowAnyException();

        verify(storage, times(3)).create(any(BlobInfo.class), any(byte[].class));
    }

    @Test
    void upload_throwsStorageUploadExceptionAfterAllAttemptsExhausted() {
        String gcsPath = "uploads/project-1/file.pdf";
        byte[] content = "pdf-content".getBytes();
        String contentType = "application/pdf";

        doThrow(new RuntimeException("persistent failure"))
                .when(storage).create(any(BlobInfo.class), any(byte[].class));

        gcpStorageService.retryDelays = new int[]{0, 0, 0};

        assertThatThrownBy(() -> gcpStorageService.upload(gcsPath, content, contentType))
                .isInstanceOf(StorageUploadException.class)
                .hasMessageContaining("Upload failed after 3 attempts");

        verify(storage, times(3)).create(any(BlobInfo.class), any(byte[].class));
    }

    @Test
    void isHealthy_returnsTrueWhenStorageReachable() {
        when(storage.get(eq(BUCKET_NAME), any(Storage.BucketGetOption.class))).thenReturn(null);

        assertThat(gcpStorageService.isHealthy()).isTrue();
    }

    @Test
    void isHealthy_returnsFalseWhenStorageThrows() {
        when(storage.get(eq(BUCKET_NAME), any(Storage.BucketGetOption.class)))
                .thenThrow(new RuntimeException("GCS unreachable"));

        assertThat(gcpStorageService.isHealthy()).isFalse();
    }

    @Test
    void generateSignedUrl_usesConfiguredExpiryDuration() throws MalformedURLException {
        String gcsPath = "uploads/project-1/file.pdf";
        int expiryMinutes = 30;
        URL stubUrl = new URL("https://storage.googleapis.com/test-bucket/file.pdf?X-Goog-Signature=xyz");

        when(storage.signUrl(
                any(BlobInfo.class),
                eq((long) expiryMinutes),
                eq(TimeUnit.MINUTES),
                any(Storage.SignUrlOption.class)
        )).thenReturn(stubUrl);

        gcpStorageService.generateSignedUrl(gcsPath, expiryMinutes);

        verify(storage).signUrl(
                any(BlobInfo.class),
                eq((long) expiryMinutes),
                eq(TimeUnit.MINUTES),
                any(Storage.SignUrlOption.class)
        );
    }
}
