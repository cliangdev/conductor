package com.conductor.config;

import com.conductor.generated.v2.model.BrandImageSlot;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedEnumConverterConfigTest {

    private final GeneratedEnumConverterConfig.GeneratedEnumConverterFactory factory =
            new GeneratedEnumConverterConfig.GeneratedEnumConverterFactory();

    @Test
    void bindsAGeneratedEnumByItsWireValue() {
        Converter<String, BrandImageSlot> converter = factory.getConverter(BrandImageSlot.class);
        assertThat(converter.convert("mark")).isEqualTo(BrandImageSlot.MARK);
        assertThat(converter.convert("wordmark_dark")).isEqualTo(BrandImageSlot.WORDMARK_DARK);
    }

    @Test
    void rejectsAnUnknownWireValue() {
        Converter<String, BrandImageSlot> converter = factory.getConverter(BrandImageSlot.class);
        assertThatThrownBy(() -> converter.convert("favicon")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leavesOtherEnumsOnValueOf() {
        Converter<String, TimeUnit> converter = factory.getConverter(TimeUnit.class);
        assertThat(converter.convert("SECONDS")).isEqualTo(TimeUnit.SECONDS);
    }
}
