package com.conductor.integration.connector.appstoreconnect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reads one app's App Store listing (what an agent needs to reason about ASO): the live version and the newest
 * editable draft with per-locale keywords / promotional text / name / subtitle, the Custom Product Pages and the
 * Product Page Optimization tests.
 *
 * <p>Rules this client keeps:
 * <ul>
 *   <li>{@code appStoreVersions} is requested with NO {@code sort} parameter (Apple answers 400); the live and
 *       draft versions are chosen client-side by parsed version number, never by response position.</li>
 *   <li>Lists follow {@code links.next}.</li>
 *   <li>Experiments degrade alone: 403/404 (account not entitled) gives {@code experimentsAvailable=false}.</li>
 *   <li>The result stays under {@link #BUDGET_BYTES} (the agent tool cuts at 8,000): locales are dropped from the
 *       end (en-US first in order) and {@code truncated} says so, with counts.</li>
 *   <li>The long description is reported by length only.</li>
 * </ul>
 */
public class AppStoreListingClient {

    private static final Logger log = LoggerFactory.getLogger(AppStoreListingClient.class);

    static final String API_BASE = "https://api.appstoreconnect.apple.com";
    static final int BUDGET_BYTES = 7_000;
    private static final int MAX_PAGES = 10;
    private static final int MAX_LIST = 25;
    private static final Set<String> LIVE_STATES = Set.of("READY_FOR_SALE", "READY_FOR_DISTRIBUTION");
    private static final Comparator<JsonNode> BY_VERSION =
            Comparator.comparing(AppStoreListingClient::version, AppStoreListingClient::compare);

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public AppStoreListingClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** Throws {@link HttpClientErrorException} when the core versions call fails. */
    public Map<String, Object> read(String token, String appId) {
        String app = encode(appId);
        List<JsonNode> versions = getAll(token, URI.create(API_BASE + "/v1/apps/" + app
                + "/appStoreVersions?" + param("filter[platform]", "IOS") + "&limit=200"));
        JsonNode live = versions.stream().filter(n -> LIVE_STATES.contains(state(n)) && version(n) != null)
                .max(BY_VERSION).orElse(null);
        String liveVersion = live == null ? null : version(live);
        JsonNode draft = versions.stream()
                .filter(n -> version(n) != null && !LIVE_STATES.contains(state(n)))
                .filter(n -> liveVersion == null || compare(version(n), liveVersion) > 0)
                .max(BY_VERSION).orElse(null);

        Map<String, String[]> appInfo = optional(() -> appInfoLocalizations(token, app), Map.of(), "app info");
        Map<String, JsonNode> liveLocs = live == null ? Map.of()
                : optional(() -> versionLocalizations(token, live), Map.of(), "live localizations");
        Map<String, JsonNode> draftLocs = draft == null ? Map.of()
                : optional(() -> versionLocalizations(token, draft), Map.of(), "draft localizations");
        List<JsonNode> cpps = optional(() -> getAll(token, URI.create(API_BASE + "/v1/apps/" + app
                + "/appCustomProductPages?limit=200")), List.of(), "custom product pages");

        boolean experimentsAvailable = true;
        List<JsonNode> experiments = List.of();
        try {
            experiments = getAll(token, URI.create(API_BASE + "/v1/apps/" + app
                    + "/appStoreVersionExperimentsV2?limit=200"));
        } catch (HttpClientErrorException e) {
            log.info("App Store experiments unavailable ({}), reporting experimentsAvailable=false", e.getStatusCode());
            experimentsAvailable = false;
        }

        Set<String> all = new LinkedHashSet<>(appInfo.keySet());
        all.addAll(liveLocs.keySet());
        all.addAll(draftLocs.keySet());
        List<String> locales = new ArrayList<>(all);
        locales.sort(Comparator.comparing((String l) -> l.equals("en-US") ? 0 : l.startsWith("en") ? 1 : 2)
                .thenComparing(Comparator.naturalOrder()));

        ObjectNode out = null;
        for (int keep = locales.size(); keep >= 0; keep--) {
            out = build(appId, live, draft, liveLocs, draftLocs, appInfo, locales, keep, cpps, experiments,
                    experimentsAvailable, keep < locales.size());
            if (size(out) <= BUDGET_BYTES) break;
        }
        return mapper.convertValue(out, new TypeReference<Map<String, Object>>() {});
    }

    private ObjectNode build(String appId, JsonNode live, JsonNode draft, Map<String, JsonNode> liveLocs,
                             Map<String, JsonNode> draftLocs, Map<String, String[]> appInfo, List<String> locales,
                             int keep, List<JsonNode> cpps, List<JsonNode> experiments, boolean experimentsAvailable,
                             boolean localesCut) {
        List<String> kept = locales.subList(0, keep);
        ObjectNode out = mapper.createObjectNode();
        out.put("appId", appId);
        out.set("live", versionNode(live, liveLocs, appInfo, kept));
        out.set("draft", versionNode(draft, draftLocs, appInfo, kept));
        ArrayNode pages = out.putArray("customProductPages");
        cpps.stream().limit(MAX_LIST).forEach(n -> {
            ObjectNode p = pages.addObject();
            p.put("id", text(n.path("id")));
            p.put("name", text(n.path("attributes").path("name")));
            p.put("visible", n.path("attributes").path("visible").asBoolean(false));
        });
        out.put("experimentsAvailable", experimentsAvailable);
        ArrayNode tests = out.putArray("experiments");
        experiments.stream().limit(MAX_LIST).forEach(n -> {
            ObjectNode t = tests.addObject();
            t.put("name", text(n.path("attributes").path("name")));
            t.put("state", text(n.path("attributes").path("state")));
        });
        out.put("localesTotal", locales.size());
        out.put("localesIncluded", keep);
        if (cpps.size() > MAX_LIST) out.put("customProductPagesTotal", cpps.size());
        if (experiments.size() > MAX_LIST) out.put("experimentsTotal", experiments.size());
        out.put("truncated", localesCut || cpps.size() > MAX_LIST || experiments.size() > MAX_LIST);
        return out;
    }

    private ObjectNode versionNode(JsonNode version, Map<String, JsonNode> locs, Map<String, String[]> appInfo,
                                   List<String> kept) {
        if (version == null) return null;
        ObjectNode v = mapper.createObjectNode();
        v.put("version", version(version));
        v.put("state", state(version));
        ArrayNode arr = v.putArray("locales");
        for (String locale : kept) {
            ObjectNode l = arr.addObject();
            l.put("locale", locale);
            String[] info = appInfo.get(locale);
            if (info != null) {
                if (info[0] != null) l.put("name", info[0]);
                if (info[1] != null) l.put("subtitle", info[1]);
            }
            JsonNode a = locs.get(locale);
            if (a != null) {
                String keywords = text(a.path("keywords"));
                if (keywords != null) l.put("keywords", keywords);
                String promo = text(a.path("promotionalText"));
                if (promo != null) l.put("promotionalText", promo);
                String desc = text(a.path("description"));
                if (desc != null) l.put("descriptionLength", desc.length());
            }
        }
        return v;
    }

    // -- calls ---------------------------------------------------------------------------------------

    private Map<String, JsonNode> versionLocalizations(String token, JsonNode version) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode n : getAll(token, URI.create(API_BASE + "/v1/appStoreVersions/"
                + encode(text(version.path("id"))) + "/appStoreVersionLocalizations?limit=200"))) {
            String locale = text(n.path("attributes").path("locale"));
            if (locale != null) out.put(locale, n.path("attributes"));
        }
        return out;
    }

    private Map<String, String[]> appInfoLocalizations(String token, String app) {
        List<JsonNode> infos = getAll(token, URI.create(API_BASE + "/v1/apps/" + app + "/appInfos?limit=200"));
        String id = infos.isEmpty() ? null : text(infos.get(0).path("id"));
        Map<String, String[]> out = new LinkedHashMap<>();
        if (id == null) return out;
        for (JsonNode n : getAll(token, URI.create(API_BASE + "/v1/appInfos/" + encode(id)
                + "/appInfoLocalizations?limit=200"))) {
            JsonNode a = n.path("attributes");
            String locale = text(a.path("locale"));
            if (locale != null) out.put(locale, new String[]{text(a.path("name")), text(a.path("subtitle"))});
        }
        return out;
    }

    /** Every {@code data} row of a JSON:API list, following {@code links.next}. */
    private List<JsonNode> getAll(String token, URI first) {
        List<JsonNode> rows = new ArrayList<>();
        URI next = first;
        int pages = 0;
        while (next != null && pages++ < MAX_PAGES) {
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(token);
            h.setAccept(List.of(MediaType.APPLICATION_JSON));
            String body = restTemplate.exchange(next, HttpMethod.GET, new HttpEntity<>(h), String.class).getBody();
            JsonNode root;
            try {
                root = mapper.readTree(body == null ? "{}" : body);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Apple returned a response that is not JSON");
            }
            root.path("data").forEach(rows::add);
            String link = text(root.path("links").path("next"));
            next = link == null ? null : URI.create(link);
        }
        return rows;
    }

    private <T> T optional(Supplier<T> call, T fallback, String what) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.info("App Store listing: {} unavailable: {}", what, e.getMessage());
            return fallback;
        }
    }

    // -- helpers -------------------------------------------------------------------------------------

    private int size(JsonNode n) {
        try {
            return mapper.writeValueAsBytes(n).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String version(JsonNode n) {
        return text(n.path("attributes").path("versionString"));
    }

    /** {@code appStoreState}, falling back to the newer {@code appVersionState}. */
    private static String state(JsonNode n) {
        String s = text(n.path("attributes").path("appStoreState"));
        return s != null ? s : text(n.path("attributes").path("appVersionState"));
    }

    static int compare(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int c = Integer.compare(i < pa.length ? part(pa[i]) : 0, i < pb.length ? part(pb[i]) : 0);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int part(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }

    private static String param(String name, String value) {
        return encode(name) + "=" + encode(value);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
