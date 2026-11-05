package com.conductor.service;

import com.conductor.exception.BusinessException;
import com.conductor.service.AppetiteParser.Appetite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppetiteParserTest {

    @ParameterizedTest
    @CsvSource({
            "2 weeks, 2 weeks, 14",
            "2w, 2 weeks, 14",
            "2 W, 2 weeks, 14",
            "1 week, 1 week, 7",
            "10 days, 10 days, 10",
            "10d, 10 days, 10",
            "1 day, 1 day, 1",
            "1 month, 1 month, 30",
            "3 months, 3 months, 90",
            "  3mo  , 3 months, 90"
    })
    void parsesTheWaysPeopleWriteIt(String raw, String label, int days) {
        Appetite parsed = AppetiteParser.parse(raw);

        assertThat(parsed.label()).isEqualTo(label);
        assertThat(parsed.days()).isEqualTo(days);
    }

    @Test
    void differentSpellingsOfOneLengthShareALabel() {
        assertThat(AppetiteParser.parse("2w")).isEqualTo(AppetiteParser.parse("2 weeks"));
    }

    @Test
    void blankOrNullMeansClear() {
        assertThat(AppetiteParser.parse("")).isNull();
        assertThat(AppetiteParser.parse("   ")).isNull();
        assertThat(AppetiteParser.parse(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"soon", "two weeks", "2", "w2", "2 years", "-3 days", "2.5 weeks", "2 weeks 3 days"})
    void rejectsWhatIsNotALength(String raw) {
        assertThatThrownBy(() -> AppetiteParser.parse(raw))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("appetite");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0 days", "0w", "366 days", "53 weeks", "13 months"})
    void rejectsLengthsOutsideOneDayToAYear(String raw) {
        assertThatThrownBy(() -> AppetiteParser.parse(raw))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("between 1 day and 365 days");
    }

    @Test
    void aYearIsTheLongestAllowed() {
        assertThat(AppetiteParser.parse("365d").days()).isEqualTo(365);
        assertThat(AppetiteParser.parse("52 weeks").days()).isEqualTo(364);
    }
}
