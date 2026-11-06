package com.conductor.marketing.report;

import com.conductor.marketing.report.BenchmarkComparability.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkComparabilityTest {

    @Test
    void theAppFollowSearchDefinitionIsNotComparableWithTheAllSourceFirstTimeConversion() {
        Result result = BenchmarkComparability.compare(
                "total_downloads", "unique_impressions", "APP_STORE_SEARCH",
                "first_time_downloads", "unique_impressions", "ALL");

        assertThat(result.comparable()).isFalse();
        assertThat(result.reason()).isEqualTo(
                "benchmark: total downloads / unique impressions (App Store search); "
                        + "metric: first-time downloads / unique impressions (all sources)");
    }

    @Test
    void theAppFollowSearchDefinitionIsComparableWithSearchOnlyTotalDownloadConversion() {
        Result result = BenchmarkComparability.compare(
                "total_downloads", "unique_impressions", "APP_STORE_SEARCH",
                "total_downloads", "unique_impressions", "APP_STORE_SEARCH");

        assertThat(result.comparable()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void aNullSourceTypeEqualsAll() {
        assertThat(BenchmarkComparability.compare("visitors", null, null, "visitors", null, "ALL").comparable()).isTrue();
        assertThat(BenchmarkComparability.compare("visitors", null, "ALL", "visitors", null, null).comparable()).isTrue();
        assertThat(BenchmarkComparability.compare("visitors", null, null, "visitors", null, null).comparable()).isTrue();
    }

    @Test
    void aBenchmarkWithoutANumeratorIsDefinitionMissingWhateverTheMetricSays() {
        Result result = BenchmarkComparability.compare(null, null, null, "first_time_downloads", "unique_impressions", "ALL");

        assertThat(result.comparable()).isFalse();
        assertThat(result.reason()).isEqualTo("definition missing");
        assertThat(BenchmarkComparability.compare(null, "unique_impressions", "ALL", null, null, null).reason())
                .isEqualTo("definition missing");
    }

    @Test
    void aMetricWithoutANumeratorIsNeverJudged() {
        Result result = BenchmarkComparability.compare("visitors", null, null, null, null, null);

        assertThat(result.comparable()).isFalse();
        assertThat(result.reason()).isEqualTo("metric definition missing");
    }

    @Test
    void aDifferentNumeratorDenominatorOrSourceTypeIsNotComparable() {
        assertThat(BenchmarkComparability.compare("taps", "impressions", null, "clicks", "impressions", null).comparable())
                .isFalse();
        assertThat(BenchmarkComparability.compare("taps", "impressions", null, "taps", "unique_impressions", null).comparable())
                .isFalse();
        assertThat(BenchmarkComparability.compare("taps", "impressions", "APP_STORE_SEARCH", "taps", "impressions", null)
                .comparable()).isFalse();
    }

    @Test
    void aCountAndARateOfTheSameNumeratorAreNotComparable() {
        Result result = BenchmarkComparability.compare("spend", null, null, "spend", "taps", null);

        assertThat(result.comparable()).isFalse();
        assertThat(result.reason()).isEqualTo("benchmark: spend (all sources); metric: spend / taps (all sources)");
    }

    @Test
    void definitionsAreComparedIgnoringCaseAndSurroundingSpace() {
        assertThat(BenchmarkComparability.compare(" Total_Downloads ", "unique_impressions", "app_store_search",
                "total_downloads", "unique_impressions", "APP_STORE_SEARCH").comparable()).isTrue();
    }

    @Test
    void aBlankDenominatorIsTreatedAsNone() {
        assertThat(BenchmarkComparability.compare("visitors", " ", null, "visitors", null, null).comparable()).isTrue();
    }

    @Test
    void describeSpellsOutMeasuresAndSources() {
        assertThat(BenchmarkComparability.describe("total_downloads", "unique_impressions", "APP_STORE_SEARCH"))
                .isEqualTo("total downloads / unique impressions (App Store search)");
        assertThat(BenchmarkComparability.describe("first_time_downloads", "unique_impressions", null))
                .isEqualTo("first-time downloads / unique impressions (all sources)");
        assertThat(BenchmarkComparability.describe("visitors", null, "ALL")).isEqualTo("visitors (all sources)");
        assertThat(BenchmarkComparability.describe("downloads_of_record", null, "APP_STORE_BROWSE"))
                .isEqualTo("downloads of record (App Store browse)");
    }

    @Test
    void describeNamesAProductFeedMeasureByItsKey() {
        assertThat(BenchmarkComparability.describe("product:trial_starts", "product:installs", null))
                .isEqualTo("trial_starts (product feed) / installs (product feed) (all sources)");
    }

    @Test
    void describeSpellsEverySourceType() {
        assertThat(BenchmarkComparability.describe("impressions", null, "APP_REFERRER")).contains("(app referrers)");
        assertThat(BenchmarkComparability.describe("impressions", null, "WEB_REFERRER")).contains("(web referrers)");
        assertThat(BenchmarkComparability.describe("impressions", null, "APP_CLIP")).contains("(app clip)");
        assertThat(BenchmarkComparability.describe("impressions", null, "INSTITUTIONAL_PURCHASE"))
                .contains("(institutional purchase)");
        assertThat(BenchmarkComparability.describe("impressions", null, "OTHER")).contains("(other)");
    }

    @Test
    void describeSaysSoWhenThereIsNoNumerator() {
        assertThat(BenchmarkComparability.describe(null, null, null)).isEqualTo("no definition");
    }

    @Test
    void theVocabularyHoldsTheContractMeasuresAndSourceTypes() {
        assertThat(BenchmarkComparability.MEASURES).contains("unique_impressions", "total_downloads",
                "first_time_downloads", "tap_installs", "position", "rating_avg");
        assertThat(BenchmarkComparability.SOURCE_TYPES).containsExactly("ALL", "APP_STORE_SEARCH", "APP_STORE_BROWSE",
                "APP_REFERRER", "WEB_REFERRER", "APP_CLIP", "INSTITUTIONAL_PURCHASE", "OTHER");
    }
}
