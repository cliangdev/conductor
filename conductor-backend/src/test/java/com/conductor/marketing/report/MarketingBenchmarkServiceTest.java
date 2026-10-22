package com.conductor.marketing.report;

import com.conductor.exception.BusinessException;
import com.conductor.marketing.report.MarketingBenchmarkService.Input;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;

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
        return new Input(null, value, null, null, sourceName, sourceUrl, checkedOn, null);
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
}
