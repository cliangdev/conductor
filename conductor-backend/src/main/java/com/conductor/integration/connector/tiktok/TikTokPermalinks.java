package com.conductor.integration.connector.tiktok;

/** The public URL of a TikTok post. Photo posts live under {@code /photo/}, videos under {@code /video/}. */
final class TikTokPermalinks {

    static final String KIND_PHOTO = "photo";
    static final String KIND_VIDEO = "video";

    private TikTokPermalinks() {}

    /** {@code null} when the handle or the post id is missing: never a guess. */
    static String url(String username, String kind, String postId) {
        if (username == null || username.isBlank() || postId == null || postId.isBlank()) {
            return null;
        }
        String segment = KIND_PHOTO.equals(kind) ? "photo" : "video";
        return "https://www.tiktok.com/@" + username + "/" + segment + "/" + postId;
    }
}
