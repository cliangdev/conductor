package com.conductor.marketing.report;

import com.conductor.exception.BusinessException;
import com.conductor.marketing.report.MarketingBenchmarkService.Input;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingBenchmarkServiceTest {

    private final MarketingBenchmarkRepository repository = mock(MarketingBenchmarkRepository.class);
    private final MarketingBenchmarkService service = new MarketingBenchmarkService(repository);

    private static Input input(Double value, String sourceName, String sourceUrl, LocalDate checkedOn) {
        return new Input(null, value, null, null, sourceName, sourceUrl, checkedOn, null, null, null, null);
    }

    private static Input defined(String numerator, String denominator, String sourceType) {
        return new Input(null, 0.124, null, null, "AppFollow", "https://example.com/study",
                LocalDate.now(ZoneOffset.UTC), null, numerator, denominator, sourceType);
    }

    private MarketingBenchmark saveDefined(Input input) {
        when(repository.findByProjectIdAndMetricRef("p1", "STORE.conversion_rate")).thenReturn(Optional.empty());
        when(repository.save(any(MarketingBenchmark.class))).thenAnswer(i -> i.getArgument(0));
        return service.upsert("p1", "STORE.conversion_rate", input, "u1");
    }

    private static Input valid() {
        return input(0.03, "Example Study", "https://example.com/study", LocalDate.now(ZoneOffset.UTC));
    }

    @Test
    void aBenchmarkWithASourceAndACheckDateIsSavedUnderANormalizedReference() {
        when(repository.findByProjectIdAndMetricRef("p1", "STORE.conversion_rate")).thenReturn(java.util.Optional.empty());
        when(repository.save(any(MarketingBenchmark.class))).thenAnswer(i -> i.getArgument(0));

        MarketingBenchmark saved = service.upsert("p1", "store.conversion_rate", valid(), "u1");

        assertThat(saved.getMetricRef()).isEqualTo("STORE.conversion_rate");
        assertThat(saved.getProjectId()).isEqualTo("p1");
        assertThat(saved.getCreatedBy()).isEqualTo("u1");
        assertThat(saved.getValue()).isEqualByComparingTo("0.03");
    }

    @Test
    void aProductReferenceIsAccepted() {
        when(repository.findByProjectIdAndMetricRef("p1", "PRODUCT.trial_to_paid")).thenReturn(java.util.Optional.empty());
        when(repository.save(any(MarketingBenchmark.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.upsert("p1", "PRODUCT.trial_to_paid", valid(), "u1").getMetricRef())
                .isEqualTo("PRODUCT.trial_to_paid");
    }

    @Test
    void aReferenceWithoutAKnownChannelIsRejected() {
        assertThatThrownBy(() -> service.upsert("p1", "conversion_rate", valid(), "u1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("CHANNEL.metric");
        assertThatThrownBy(() -> service.upsert("p1", "RADIO.reach", valid(), "u1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Unknown channel");
        assertThatThrownBy(() -> service.upsert("p1", "STORE.", valid(), "u1"))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void theSourceUrlMustBeHttpOrHttps() {
        for (String url : new String[] {null, "", "  ", "ftp://example.com/x", "javascript:alert(1)", "example.com/x",
                "https://"}) {
            assertThatThrownBy(() -> service.upsert("p1", "STORE.downloads",
                    input(1.0, "Study", url, LocalDate.now(ZoneOffset.UTC)), "u1"))
                    .as("url %s", url).isInstanceOf(BusinessException.class);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void theCheckDateIsRequiredAndCannotBeInTheFuture() {
        assertThatThrownBy(() -> service.upsert("p1", "STORE.downloads",
                input(1.0, "Study", "https://example.com", null), "u1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("date");
        assertThatThrownBy(() -> service.upsert("p1", "STORE.downloads",
                input(1.0, "Study", "https://example.com", LocalDate.now(ZoneOffset.UTC).plusDays(2)), "u1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("future");
    }

    @Test
    void aSourceNameAndAFiniteValueAreRequired() {
        assertThatThrownBy(() -> service.upsert("p1", "STORE.downloads",
                input(1.0, " ", "https://example.com", LocalDate.now(ZoneOffset.UTC)), "u1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("source");
        assertThatThrownBy(() -> service.upsert("p1", "STORE.downloads",
                input(null, "Study", "https://example.com", LocalDate.now(ZoneOffset.UTC)), "u1"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upsert("p1", "STORE.downloads",
                input(Double.NaN, "Study", "https://example.com", LocalDate.now(ZoneOffset.UTC)), "u1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void theAppFollowDefinitionIsStored() {
        MarketingBenchmark saved = saveDefined(defined("total_downloads", "unique_impressions", "APP_STORE_SEARCH"));

        assertThat(saved.getNumerator()).isEqualTo("total_downloads");
        assertThat(saved.getDenominator()).isEqualTo("unique_impressions");
        assertThat(saved.getSourceType()).isEqualTo("APP_STORE_SEARCH");
    }

    @Test
    void aBenchmarkWithoutADefinitionIsStillAccepted() {
        MarketingBenchmark saved = saveDefined(valid());

        assertThat(saved.getNumerator()).isNull();
        assertThat(saved.getDenominator()).isNull();
        assertThat(saved.getSourceType()).isNull();
    }

    @Test
    void aCountBenchmarkNeedsOnlyANumerator() {
        MarketingBenchmark saved = saveDefined(defined("spend", null, null));

        assertThat(saved.getNumerator()).isEqualTo("spend");
        assertThat(saved.getDenominator()).isNull();
        assertThat(saved.getSourceType()).isNull();
    }

    @Test
    void aProductFeedMeasureIsAcceptedAsNumeratorAndDenominator() {
        MarketingBenchmark saved = saveDefined(defined("product:trial_starts", "product:installs", null));

        assertThat(saved.getNumerator()).isEqualTo("product:trial_starts");
        assertThat(saved.getDenominator()).isEqualTo("product:installs");
    }

    @Test
    void anUnknownMeasureIsRefusedWithTheAllowedValues() {
        assertThatThrownBy(() -> saveDefined(defined("downloadz", "unique_impressions", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("numerator 'downloadz'")
                .hasMessageContaining("total_downloads")
                .hasMessageContaining("unique_impressions")
                .hasMessageContaining("product:<key>");
        assertThatThrownBy(() -> saveDefined(defined("total_downloads", "visits_of_doom", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("denominator 'visits_of_doom'")
                .hasMessageContaining("first_time_downloads");
        verify(repository, never()).save(any());
    }

    @Test
    void aSourceTypeThatIsNotInTheVocabularyIsRefusedWithTheAllowedValues() {
        assertThatThrownBy(() -> saveDefined(defined("total_downloads", null, "APP_STORE_SERACH")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sourceType 'APP_STORE_SERACH'")
                .hasMessageContaining("APP_STORE_SEARCH")
                .hasMessageContaining("INSTITUTIONAL_PURCHASE");
        verify(repository, never()).save(any());
    }

    @Test
    void aMeasureNameIsNotAcceptedAsASourceTypeOrTheOtherWayAround() {
        assertThatThrownBy(() -> saveDefined(defined("total_downloads", null, "impressions")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("sourceType");
        assertThatThrownBy(() -> saveDefined(defined("APP_STORE_SEARCH", null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("numerator");
    }

    @Test
    void aProductKeyMustBeLowerSnakeCase() {
        for (String bad : new String[] {"product:", "product:1abc", "product:has space", "product:a-b",
                "product:" + "a".repeat(65)}) {
            assertThatThrownBy(() -> saveDefined(defined(bad, null, null)))
                    .as("numerator %s", bad).isInstanceOf(BusinessException.class).hasMessageContaining("product:<key>");
        }
        assertThat(saveDefined(defined("product:" + "a".repeat(64), null, null)).getNumerator()).hasSize(72);
    }

    @Test
    void aDenominatorWithoutANumeratorIsRefused() {
        assertThatThrownBy(() -> saveDefined(defined(null, "unique_impressions", null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("numerator is required");
        verify(repository, never()).save(any());
    }

    @Test
    void aSourceTypeWithoutANumeratorIsRefused() {
        assertThatThrownBy(() -> saveDefined(defined(null, null, "APP_STORE_SEARCH")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("numerator is required");
        verify(repository, never()).save(any());
    }

    @Test
    void blankDefinitionFieldsCountAsAbsentAndCaseIsNormalised() {
        MarketingBenchmark blank = saveDefined(defined("  ", " ", ""));
        assertThat(blank.getNumerator()).isNull();
        assertThat(blank.getDenominator()).isNull();
        assertThat(blank.getSourceType()).isNull();

        MarketingBenchmark loose = saveDefined(defined(" Total_Downloads ", "Unique_Impressions", "app_store_search"));
        assertThat(loose.getNumerator()).isEqualTo("total_downloads");
        assertThat(loose.getDenominator()).isEqualTo("unique_impressions");
        assertThat(loose.getSourceType()).isEqualTo("APP_STORE_SEARCH");
    }

    @Test
    void anUpdateIsAFullReplaceSoOmittingTheDefinitionClearsIt() {
        MarketingBenchmark existing = new MarketingBenchmark();
        existing.setProjectId("p1");
        existing.setMetricRef("STORE.conversion_rate");
        existing.setNumerator("total_downloads");
        existing.setDenominator("unique_impressions");
        existing.setSourceType("APP_STORE_SEARCH");
        when(repository.findByProjectIdAndMetricRef("p1", "STORE.conversion_rate")).thenReturn(Optional.of(existing));
        when(repository.save(any(MarketingBenchmark.class))).thenAnswer(i -> i.getArgument(0));

        MarketingBenchmark saved = service.upsert("p1", "STORE.conversion_rate", valid(), "u1");

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getNumerator()).isNull();
        assertThat(saved.getDenominator()).isNull();
        assertThat(saved.getSourceType()).isNull();
    }

    @Test
    void anUpdateWithAnInvalidDefinitionLeavesTheStoredOneUntouched() {
        MarketingBenchmark existing = new MarketingBenchmark();
        existing.setNumerator("total_downloads");
        when(repository.findByProjectIdAndMetricRef("p1", "STORE.conversion_rate")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.upsert("p1", "STORE.conversion_rate", defined("nope", null, null), "u1"))
                .isInstanceOf(BusinessException.class);

        assertThat(existing.getNumerator()).isEqualTo("total_downloads");
        verify(repository, never()).save(any());
    }
}
