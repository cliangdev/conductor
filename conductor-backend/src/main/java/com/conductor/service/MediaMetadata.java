package com.conductor.service;

import com.conductor.entity.Asset;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The intrinsic shape of an uploaded media file (COND-23 T5.1): pixel dimensions and, for video, running
 * time. These are the only facts the per-platform publish rules in {@link MediaTargetValidator} need beyond
 * the content type and byte count already on the {@link Asset} row.
 *
 * <p><b>Every component is nullable, and null means "not known".</b> It deliberately does <em>not</em> mean
 * "fine" — {@link MediaTargetValidator} refuses to approve a Post whose media it cannot measure against a
 * rule that applies to it, because the alternative is a silent failure at fire time, which is the exact
 * outcome the publishing pipeline exists to prevent.
 *
 * <h2>How these get populated</h2>
 * <ul>
 *   <li><b>Images</b> — derived server-side at {@link AssetService#confirmUpload}, which downloads the
 *       just-uploaded object and reads its header with {@link #probeImage}. That uses only
 *       {@link ImageIO} from the JDK (no new dependency), and it is authoritative: it overrides anything the
 *       client claimed, because the bytes in the bucket are the bytes the platform will receive. JPEG, PNG,
 *       GIF and BMP are readable out of the box; WebP is not, and falls back to the client-declared value.</li>
 *   <li><b>Video</b> — <em>not</em> derivable server-side: no container/codec parser ships with the JDK and
 *       adding one (or shelling out to ffprobe) is a heavyweight dependency this task deliberately avoids.
 *       Duration and dimensions are therefore declared by the uploading client on the create path
 *       ({@link AssetService.FileAssetInput}), where a browser already knows them from the
 *       {@code HTMLVideoElement} metadata it loads to render the file picker preview.</li>
 * </ul>
 *
 * <p>The fallback when a client declares nothing is null, and a null is a blocked approval with a message
 * naming the file and the rule that could not be checked — loud, at review time, and fixable by re-uploading.
 */
public record MediaMetadata(Integer width, Integer height, BigDecimal durationSeconds) {

    /** Nothing is known about this file's shape. */
    public static final MediaMetadata UNKNOWN = new MediaMetadata(null, null, null);

    /** Largest object {@link AssetService} will pull back out of storage just to read an image header. */
    public static final long MAX_PROBE_BYTES = 32L * 1024 * 1024;

    /** The shape recorded on an Asset row, or {@link #UNKNOWN} when the row carries none. */
    public static MediaMetadata of(Asset asset) {
        if (asset == null) {
            return UNKNOWN;
        }
        return new MediaMetadata(asset.getWidth(), asset.getHeight(), asset.getDurationSeconds());
    }

    public boolean hasDimensions() {
        return width != null && width > 0 && height != null && height > 0;
    }

    public boolean hasDuration() {
        return durationSeconds != null && durationSeconds.signum() > 0;
    }

    /** Width divided by height — the orientation-independent number the platform aspect rules are stated in. */
    public OptionalDouble aspectRatio() {
        return hasDimensions() ? OptionalDouble.of((double) width / (double) height) : OptionalDouble.empty();
    }

    /** True when the frame is taller than it is wide, or exactly square. Unknown dimensions are never either. */
    public boolean isPortraitOrSquare() {
        return hasDimensions() && height >= width;
    }

    /**
     * Reads pixel dimensions straight out of an image's header using the JDK's own {@link ImageIO} readers.
     * Best effort by design: unreadable bytes, an unsupported format (WebP) or a truncated upload yield
     * {@link Optional#empty()} rather than an exception, and the caller leaves the row's dimensions as they
     * were. Only the header is decoded — the pixels are never rasterized — so this stays cheap on large files.
     */
    public static Optional<MediaMetadata> probeImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) {
                return Optional.empty();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    return Optional.empty();
                }
                return Optional.of(new MediaMetadata(width, height, null));
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
