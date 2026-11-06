package com.conductor.marketing.overview;

import com.conductor.entity.ChannelMetricDefinitionRow;
import com.conductor.integration.MetricChannel;
import com.conductor.marketing.overview.MetricCatalog.Aggregation;
import com.conductor.marketing.overview.MetricCatalog.Channel;
import com.conductor.marketing.overview.MetricCatalog.Definition;
import com.conductor.marketing.overview.MetricCatalog.Metric;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The catalog's metric definitions: one per metric, in the closed measure vocabulary of the shared contract. */
class MetricCatalogDefinitionsTest {

    private static Metric metric(MetricChannel channel, String key) {
        return MetricCatalog.channel(channel).orElseThrow().metric(key).orElseThrow();
    }

    private static ChannelMetricDefinitionRow feed(String connectionId, String metric, String aggregation,
                                                   String numerator, String denominator, String exclusions) {
        ChannelMetricDefinitionRow d = new ChannelMetricDefinitionRow();
        d.setConnectionId(connectionId);
        d.setProjectId("p1");
        d.setMetric(metric);
        d.setLabel(metric);
        d.setUnit("COUNT");
        d.setAggregation(aggregation);
        d.setNumerator(numerator);
        d.setDenominator(denominator);
        d.setGood("UP");
        d.setExclusions(exclusions);
        return d;
    }

    @Test
    void everyCatalogMetricHiddenOnesIncludedHasADefinitionInTheMeasureVocabulary() {
        for (Channel channel : MetricCatalog.channels()) {
            for (Metric m : channel.metrics()) {
                String where = channel.channel() + "." + m.key();
                Definition d = m.definition();
                assertThat(d).as(where + " has a definition").isNotNull();
                assertThat(d.source()).as(where + " source").isNotBlank();
                assertThat(MetricCatalog.isMeasure(d.numerator())).as(where + " numerator " + d.numerator()).isTrue();
                if (d.denominator() != null) {
                    assertThat(MetricCatalog.isMeasure(d.denominator()))
                            .as(where + " denominator " + d.denominator()).isTrue();
                }
                assertThat(d.exclusions() == null || !d.exclusions().isBlank()).as(where + " exclusions").isTrue();
                assertThat(d.sourceType() == null || !d.sourceType().isBlank()).as(where + " sourceType").isTrue();
            }
        }
    }

    @Test
    void aDerivedMetricsComponentsAreMetricsOfItsOwnChannel() {
        for (Channel channel : MetricCatalog.channels()) {
            for (Metric m : channel.metrics()) {
                if (m.agg() == Aggregation.DERIVED) {
                    assertThat(channel.metric(m.numerator())).as(m.key() + " numerator").isPresent();
                    assertThat(channel.metric(m.denominator())).as(m.key() + " denominator").isPresent();
                }
            }
        }
    }

    @Test
    void aChannelWhoseMetricsReadOneDimensionKeyLoadsDimensionRows() {
        assertThat(MetricCatalog.channel(MetricChannel.STORE).orElseThrow().usesDimensionSeries()).isTrue();
        assertThat(MetricCatalog.channel(MetricChannel.ADS).orElseThrow().usesDimensionSeries()).isFalse();
        for (Channel channel : MetricCatalog.channels()) {
            boolean readsDimensionKey = channel.metrics().stream().anyMatch(m -> m.dimensionKey() != null);
            if (readsDimensionKey) {
                assertThat(channel.usesDimensionSeries()).as(channel.channel().name()).isTrue();
            }
        }
    }

    @Test
    void theMeasureVocabularyIsClosedAndAcceptsProductFeedMetrics() {
        assertThat(MetricCatalog.isMeasure("tap_installs")).isTrue();
        assertThat(MetricCatalog.isMeasure("unique_impressions")).isTrue();
        assertThat(MetricCatalog.isMeasure("product:trial_started")).isTrue();
        assertThat(MetricCatalog.isMeasure("installs")).isFalse();
        assertThat(MetricCatalog.isMeasure("product:")).isFalse();
        assertThat(MetricCatalog.isMeasure("")).isFalse();
        assertThat(MetricCatalog.isMeasure(null)).isFalse();
        assertThat(MetricCatalog.MEASURES).contains("impressions", "first_time_downloads", "total_downloads",
                "downloads_of_record", "view_through_installs", "conversion_sessions", "likes", "position");
    }

    @Test
    void adsInstallsAreTapInstallsFromTheCampaignReportInUtc() {
        Definition installs = metric(MetricChannel.ADS, "installs").definition();

        assertThat(installs.source()).isEqualTo(
                "Apple Ads campaign report, tapInstalls (tap new downloads + tap redownloads)");
        assertThat(installs.numerator()).isEqualTo("tap_installs");
        assertThat(installs.denominator()).isNull();
        assertThat(installs.timeZone()).isEqualTo("UTC");
        Definition cpi = metric(MetricChannel.ADS, "cost_per_install").definition();
        assertThat(cpi.numerator()).isEqualTo("spend");
        assertThat(cpi.denominator()).isEqualTo("tap_installs");
    }

    @Test
    void adsViewThroughInstallsAndFirstTimeDownloadsAreOptionalSumsKeptApartFromTapInstalls() {
        Metric view = metric(MetricChannel.ADS, "view_installs");
        Metric firstTime = metric(MetricChannel.ADS, "new_downloads");

        assertThat(view.label()).isEqualTo("View-through installs");
        assertThat(view.agg()).isEqualTo(Aggregation.SUM);
        assertThat(view.optional()).isTrue();
        assertThat(view.definition().numerator()).isEqualTo("view_through_installs");
        assertThat(firstTime.label()).isEqualTo("First-time downloads");
        assertThat(firstTime.optional()).isTrue();
        assertThat(firstTime.definition().numerator()).isEqualTo("tap_new_downloads");
    }

    @Test
    void adsKeywordAndAdTablesCountTapInstallsLikeCampaignsAndAddFirstTimeDownloadsAndSearchTerms() {
        Channel ads = MetricCatalog.channel(MetricChannel.ADS).orElseThrow();

        for (String table : List.of("keywords", "ads")) {
            var dimension = ads.dimensions().stream().filter(d -> d.key().equals(table)).findFirst().orElseThrow();
            assertThat(dimension.columns()).filteredOn(c -> c.key().equals("installs"))
                    .singleElement().satisfies(c -> assertThat(c.label()).isEqualTo("Installs"));
            assertThat(dimension.columns()).filteredOn(c -> c.key().equals("new_downloads"))
                    .singleElement().satisfies(c -> {
                        assertThat(c.label()).isEqualTo("First-time downloads");
                        assertThat(c.source()).isEqualTo("new_downloads");
                    });
        }
        var terms = ads.dimensions().stream().filter(d -> d.key().equals("searchterms")).findFirst().orElseThrow();
        assertThat(terms.label()).isEqualTo("Search terms");
        assertThat(terms.keyPrefix()).isEqualTo("searchterm:");
        assertThat(terms.columns()).extracting(MetricCatalog.Column::key)
                .containsExactly("impressions", "taps", "installs", "spend", "cost_per_install");
    }

    @Test
    void webVisitorsAreAWindowUniqueCountWithTheTeamTrafficExclusions() {
        Metric visitors = metric(MetricChannel.WEB, "visitors");

        assertThat(visitors.agg()).isEqualTo(Aggregation.WINDOW_UNIQUE);
        assertThat(visitors.definition().source())
                .isEqualTo("PostHog: distinct persons with a page view in the window");
        assertThat(visitors.definition().numerator()).isEqualTo("visitors");
        assertThat(visitors.definition().exclusions())
                .isEqualTo("Internal-users cohort and excluded share-page paths, when set on the connection");
        Metric conversionSessions = metric(MetricChannel.WEB, "conversion_sessions");
        assertThat(conversionSessions.optional()).isTrue();
        assertThat(conversionSessions.agg()).isEqualTo(Aggregation.SUM);
        assertThat(conversionSessions.label()).isEqualTo("Conversion sessions");
    }

    @Test
    void storeConversionRateIsFirstTimeDownloadsOverUniqueImpressionsAcrossAllSources() {
        Definition d = metric(MetricChannel.STORE, "conversion_rate").definition();

        assertThat(d.numerator()).isEqualTo("first_time_downloads");
        assertThat(d.denominator()).isEqualTo("unique_impressions");
        assertThat(d.sourceType()).isEqualTo("ALL");
        assertThat(d.sourceTypeOrAll()).isEqualTo("ALL");
    }

    @Test
    void eachPerSourceConversionRateIsTotalDownloadsOverUniqueImpressionsForThatOneSourceType() {
        Map<String, String> expected = Map.of(
                "conversion_rate_search", "APP_STORE_SEARCH",
                "conversion_rate_browse", "APP_STORE_BROWSE",
                "conversion_rate_referrer", "APP_REFERRER",
                "conversion_rate_web", "WEB_REFERRER");

        expected.forEach((key, sourceType) -> {
            Metric m = metric(MetricChannel.STORE, key);
            assertThat(m.agg()).as(key).isEqualTo(Aggregation.DERIVED);
            assertThat(m.optional()).as(key + " is hidden until rows exist").isTrue();
            assertThat(m.hidden()).isFalse();
            assertThat(m.definition().numerator()).isEqualTo("total_downloads");
            assertThat(m.definition().denominator()).isEqualTo("unique_impressions");
            assertThat(m.definition().sourceType()).isEqualTo(sourceType);
            Channel store = MetricCatalog.channel(MetricChannel.STORE).orElseThrow();
            Metric numerator = store.metric(m.numerator()).orElseThrow();
            Metric denominator = store.metric(m.denominator()).orElseThrow();
            assertThat(numerator.hidden()).isTrue();
            assertThat(numerator.storedKey()).isEqualTo("total_downloads");
            assertThat(numerator.dimensionKey()).isEqualTo("source:" + sourceType);
            assertThat(denominator.hidden()).isTrue();
            assertThat(denominator.storedKey()).isEqualTo("impressions_unique");
            assertThat(denominator.dimensionKey()).isEqualTo("source:" + sourceType);
        });
    }

    @Test
    void storeDownloadsOfRecordComeFromSalesReports() {
        Metric m = metric(MetricChannel.STORE, "downloads_of_record");

        assertThat(m.label()).isEqualTo("Downloads of record");
        assertThat(m.optional()).isTrue();
        assertThat(m.definition().source()).isEqualTo("App Store Connect Sales Reports, first-time downloads");
        assertThat(metric(MetricChannel.STORE, "total_downloads").hidden()).isTrue();
        assertThat(metric(MetricChannel.STORE, "sales_report_days").hidden()).isTrue();
    }

    @Test
    void socialListsTikTokAndLikesAsAnOptionalLevelWithALikesColumn() {
        Channel social = MetricCatalog.channel(MetricChannel.SOCIAL).orElseThrow();

        assertThat(social.connectorIds()).containsExactly("meta", "youtube", "tiktok");
        assertThat(MetricCatalog.connectorName("tiktok")).isEqualTo("TikTok");
        Metric likes = social.metric("likes").orElseThrow();
        assertThat(likes.label()).isEqualTo("Likes");
        assertThat(likes.agg()).isEqualTo(Aggregation.LEVEL);
        assertThat(likes.optional()).isTrue();
        assertThat(social.dimensions().get(0).columns()).extracting(MetricCatalog.Column::key).contains("likes");
    }

    @Test
    void aProductMetricsDefinitionNamesItsFeedMetricAndCarriesTheFeedsExclusions() {
        ChannelMetricDefinitionRow trials = feed("c1", "trial_started", "SUM", null, null,
                "Internal test installs and likely App Review installs");
        ChannelMetricDefinitionRow installs = feed("c1", "installs", "SUM", null, null, null);
        ChannelMetricDefinitionRow rate = feed("c2", "trial_rate", "RATIO", "trial_started", "installs", null);

        Channel product = MetricCatalog.productChannel(List.of(trials, installs, rate), Map.of("c1", "Rexipe"));

        Definition sum = product.metric("trial_started").orElseThrow().definition();
        assertThat(sum.source()).isEqualTo("Product metrics feed (Rexipe)");
        assertThat(sum.numerator()).isEqualTo("product:trial_started");
        assertThat(sum.denominator()).isNull();
        assertThat(sum.exclusions()).isEqualTo("Internal test installs and likely App Review installs");
        assertThat(product.metric("installs").orElseThrow().definition().exclusions()).isNull();
        Definition ratio = product.metric("trial_rate").orElseThrow().definition();
        assertThat(ratio.numerator()).isEqualTo("product:trial_started");
        assertThat(ratio.denominator()).isEqualTo("product:installs");
        assertThat(ratio.source()).isEqualTo("Product metrics feed (product backend)");
        product.metrics().forEach(m -> {
            assertThat(MetricCatalog.isMeasure(m.definition().numerator())).isTrue();
            assertThat(m.definition().denominator() == null || MetricCatalog.isMeasure(m.definition().denominator()))
                    .isTrue();
        });
    }

    @Test
    void aBlankFeedExclusionIsNoExclusion() {
        ChannelMetricDefinitionRow d = feed("c1", "trial_started", "LEVEL", null, null, "  ");

        Channel product = MetricCatalog.productChannel(List.of(d));

        Definition def = product.metric("trial_started").orElseThrow().definition();
        assertThat(def.exclusions()).isNull();
        assertThat(def.source()).isEqualTo("Product metrics feed (product backend)");
        assertThat(def.numerator()).isEqualTo("product:trial_started");
    }
}
