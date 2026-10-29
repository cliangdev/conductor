package com.conductor.service;

import com.conductor.exception.StorageUploadException;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
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
import static org.mockito.Mockito.mock;
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
    void upload_htmlSetsContentDispositionAttachment() {
        gcpStorageService.upload("proj/issues/wi/doc/prd.html", "<p>hi</p>".getBytes(), "text/html");

        ArgumentCaptor<BlobInfo> captor = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).create(captor.capture(), any(byte[].class));
        assertThat(captor.getValue().getContentType()).isEqualTo("text/html");
        assertThat(captor.getValue().getContentDisposition()).isEqualTo("attachment");
    }

    @Test
    void upload_htmlWithCharsetSetsContentDispositionAttachment() {
        gcpStorageService.upload("proj/issues/wi/doc/prd.html", "<p>hi</p>".getBytes(), "text/html; charset=utf-8");

        ArgumentCaptor<BlobInfo> captor = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).create(captor.capture(), any(byte[].class));
        assertThat(captor.getValue().getContentDisposition()).isEqualTo("attachment");
    }

    @Test
    void upload_nonHtmlLeavesContentDispositionUnset() {
        gcpStorageService.upload("proj/issues/wi/doc/spec.md", "# hi".getBytes(), "text/markdown");

        ArgumentCaptor<BlobInfo> captor = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).create(captor.capture(), any(byte[].class));
        assertThat(captor.getValue().getContentDisposition()).isNull();
    }

    @Test
    void delete_whenStorageThrows_propagatesException() {
        // Contract: a real backend failure must reach the caller so it can keep its stored
        // reference and retry, instead of orphaning the object (see StorageService#delete).
        String gcsPath = "uploads/project-1/file.pdf";
        doThrow(new RuntimeException("GCS error")).when(storage).delete(any(BlobId.class));

        assertThatThrownBy(() -> gcpStorageService.delete(gcsPath))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("GCS error");
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
    @SuppressWarnings("unchecked")
    void isHealthy_returnsTrueWhenObjectListingSucceeds() {
        Page<Blob> page = mock(Page.class);
        when(page.getValues()).thenReturn(java.util.Collections.emptyList());
        when(storage.list(eq(BUCKET_NAME), any(Storage.BlobListOption.class))).thenReturn(page);

        assertThat(gcpStorageService.isHealthy()).isTrue();
    }

    @Test
    void isHealthy_returnsFalseWhenStorageThrows() {
        when(storage.list(eq(BUCKET_NAME), any(Storage.BlobListOption.class)))
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

    // ---- signed URL cache, Cache-Control ----

    private static final class MutableClock extends java.time.Clock {
        long now = 1_000_000L;
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(now); }
    }

    private void stubSignUrlDistinctPerCall() throws MalformedURLException {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.lenient().when(storage.signUrl(any(BlobInfo.class), any(Long.class), eq(TimeUnit.MINUTES), any(Storage.SignUrlOption[].class)))
                .thenAnswer(inv -> new URL("https://storage.googleapis.com/b/o?sig=" + n.incrementAndGet()));
    }

    @Test
    void generateSignedUrl_reusesUrlWithinWindowAndResignsAfterIt() throws MalformedURLException {
        MutableClock clock = new MutableClock();
        GcpStorageService svc = new GcpStorageService(storage, BUCKET_NAME, clock);
        stubSignUrlDistinctPerCall();

        String first = svc.generateSignedUrl("a/b.png", 60);
        clock.now += 44 * 60_000L; // under 75% of 60 minutes
        assertThat(svc.generateSignedUrl("a/b.png", 60)).isEqualTo(first);
        verify(storage, times(1)).signUrl(any(BlobInfo.class), any(Long.class), any(TimeUnit.class), any(Storage.SignUrlOption.class));

        clock.now += 2 * 60_000L; // past 45 minutes
        assertThat(svc.generateSignedUrl("a/b.png", 60)).isNotEqualTo(first);
    }

    @Test
    void generateSignedUrl_keysOnPathAndExpiry() throws MalformedURLException {
        GcpStorageService svc = new GcpStorageService(storage, BUCKET_NAME, new MutableClock());
        stubSignUrlDistinctPerCall();

        String a = svc.generateSignedUrl("a.png", 60);
        assertThat(svc.generateSignedUrl("b.png", 60)).isNotEqualTo(a);
        assertThat(svc.generateSignedUrl("a.png", 90)).isNotEqualTo(a);
    }

    @Test
    void generateSignedUploadUrl_isNeverCached() throws MalformedURLException {
        GcpStorageService svc = new GcpStorageService(storage, BUCKET_NAME, new MutableClock());
        stubSignUrlDistinctPerCall();

        String first = svc.generateSignedUploadUrl("a.png", "image/png", 60);
        String second = svc.generateSignedUploadUrl("a.png", "image/png", 60);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void delete_evictsCachedSignedUrls() throws MalformedURLException {
        GcpStorageService svc = new GcpStorageService(storage, BUCKET_NAME, new MutableClock());
        stubSignUrlDistinctPerCall();

        String first = svc.generateSignedUrl("a.png", 60);
        svc.delete("a.png");

        assertThat(svc.generateSignedUrl("a.png", 60)).isNotEqualTo(first);
    }

    @Test
    void upload_setsPrivateCacheControl() {
        gcpStorageService.upload("a.png", new byte[]{1}, "image/png");

        ArgumentCaptor<BlobInfo> captor = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).create(captor.capture(), any(byte[].class));
        assertThat(captor.getValue().getCacheControl()).isEqualTo("private, max-age=86400");
    }

    @Test
    void markCacheable_updatesBlobMetadata() {
        gcpStorageService.markCacheable("a.png");

        ArgumentCaptor<BlobInfo> captor = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).update(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("a.png");
        assertThat(captor.getValue().getCacheControl()).isEqualTo("private, max-age=86400");
    }

    @Test
    void markCacheable_swallowsFailures() {
        when(storage.update(any(BlobInfo.class))).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> gcpStorageService.markCacheable("a.png")).doesNotThrowAnyException();
    }
}
