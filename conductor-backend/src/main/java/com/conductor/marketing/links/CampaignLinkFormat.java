package com.conductor.marketing.links;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The shape of an App Store campaign link and of the tokens it carries.
 *
 * <p>Apple's {@code ct} (campaign token) is at most 40 characters; the tokens here are further limited to lowercase
 * letters, digits and dashes so that what appears in a URL, in Apple's report and in Conductor is one and the same
 * string, never a re-cased or re-encoded variant of it.
 */
final class CampaignLinkFormat {

    static final int MAX_TOKEN = 40;
    static final int MAX_FAMILY = 64;

    private static final Pattern TOKEN = Pattern.compile("^[a-z0-9][a-z0-9-]{0," + (MAX_TOKEN - 1) + "}$");
    private static final Pattern FAMILY = Pattern.compile("^[a-z0-9][a-z0-9-]{0," + (MAX_FAMILY - 1) + "}$");
    private static final Pattern NOT_TOKEN_CHARACTER = Pattern.compile("[^a-z0-9-]");
    private static final String POST_PREFIX = "post-";

    private CampaignLinkFormat() {
    }

    static boolean isToken(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    static boolean isFamily(String value) {
        return value != null && FAMILY.matcher(value).matches();
    }

    /** {@code https://apps.apple.com/app/apple-store/id{appId}?pt={providerToken}&ct={token}}. */
    static String url(String appId, String providerToken, String token) {
        return "https://apps.apple.com/app/apple-store/id" + appId
                + "?pt=" + URLEncoder.encode(providerToken.trim(), StandardCharsets.UTF_8)
                + "&ct=" + token;
    }

    /**
     * The token of a Post's automatic link: {@code post-{project key lowercased}-{sequence number}}, cut to
     * {@link #MAX_TOKEN} and never ending on a dash. A project key is at most 10 characters, so the cut only
     * guards a malformed key.
     */
    static String postToken(String projectKey, int sequenceNumber) {
        String key = NOT_TOKEN_CHARACTER.matcher(projectKey.toLowerCase(Locale.ROOT)).replaceAll("");
        String token = POST_PREFIX + key + "-" + sequenceNumber;
        if (token.length() > MAX_TOKEN) {
            token = token.substring(0, MAX_TOKEN);
        }
        while (token.endsWith("-")) {
            token = token.substring(0, token.length() - 1);
        }
        return token;
    }
}
