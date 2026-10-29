package com.conductor.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentContentTest {

    @Test
    void htmlByContentType() {
        assertThat(DocumentContent.isHtml("text/html", "prd")).isTrue();
        assertThat(DocumentContent.isHtml("TEXT/HTML; charset=utf-8", "prd")).isTrue();
    }

    @Test
    void htmlByFilenameExtensionCaseInsensitive() {
        assertThat(DocumentContent.isHtml("text/markdown", "prd.html")).isTrue();
        assertThat(DocumentContent.isHtml("text/markdown", "Prd.HTM")).isTrue();
        assertThat(DocumentContent.isHtml(null, "mockup.Html")).isTrue();
    }

    @Test
    void notHtml() {
        assertThat(DocumentContent.isHtml("text/markdown", "prd.md")).isFalse();
        assertThat(DocumentContent.isHtml("text/plain", "notes.html.txt")).isFalse();
        assertThat(DocumentContent.isHtml("application/json", "htmlish")).isFalse();
        assertThat(DocumentContent.isHtml(null, null)).isFalse();
    }

    @Test
    void sha256HexOfUtf8Content() {
        assertThat(DocumentContent.sha256Hex("hello"))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(DocumentContent.sha256Hex("")) // SHA-256 of the empty string
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(DocumentContent.sha256Hex("héllo")).hasSize(64).isNotEqualTo(DocumentContent.sha256Hex("hello"));
    }

    @Test
    void sha256HexOfNullIsNull() {
        assertThat(DocumentContent.sha256Hex(null)).isNull();
    }
}
