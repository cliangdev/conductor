package com.conductor.marketing.links;

import com.conductor.integration.connector.appstoreconnect.AppStoreConnectConnector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The shape of a campaign link and its tokens, and the connector field the provider token is entered in. */
class CampaignLinkFormatTest {

    @Test
    void theUrlIsTheAppStoreCampaignLinkWithTheProviderTokenAndTheCampaignToken() {
        assertThat(CampaignLinkFormat.url("1234567890", "117962345", "post-rx-12"))
                .isEqualTo("https://apps.apple.com/app/apple-store/id1234567890?pt=117962345&ct=post-rx-12");
    }

    @Test
    void aProviderTokenIsTrimmedAndEncodedSoItCannotBreakOutOfTheQuery() {
        assertThat(CampaignLinkFormat.url("42", " 12&x=3 ", "t"))
                .isEqualTo("https://apps.apple.com/app/apple-store/id42?pt=12%26x%3D3&ct=t");
    }

    @Test
    void aTokenIsLowercaseLettersDigitsAndDashesStartingWithALetterOrDigit() {
        for (String ok : new String[] {"a", "0", "ig-bio", "post-rx-12", "a".repeat(40)}) {
            assertThat(CampaignLinkFormat.isToken(ok)).as(ok).isTrue();
        }
        for (String bad : new String[] {"", "-a", "A", "a_b", "a b", "é", "a".repeat(41)}) {
            assertThat(CampaignLinkFormat.isToken(bad)).as(bad).isFalse();
        }
        assertThat(CampaignLinkFormat.isToken(null)).isFalse();
    }

    @Test
    void aFamilyAllowsSixtyFourCharacters() {
        assertThat(CampaignLinkFormat.isFamily("a".repeat(64))).isTrue();
        assertThat(CampaignLinkFormat.isFamily("a".repeat(65))).isFalse();
        assertThat(CampaignLinkFormat.isFamily("Posts")).isFalse();
        assertThat(CampaignLinkFormat.isFamily(null)).isFalse();
    }

    @Test
    void aPostTokenIsPostThenTheLowercasedProjectKeyThenTheSequenceNumber() {
        assertThat(CampaignLinkFormat.postToken("RE", 99)).isEqualTo("post-re-99");
        assertThat(CampaignLinkFormat.postToken("MK-1_x", 7)).isEqualTo("post-mk-1x-7");
    }

    @Test
    void aPostTokenIsTrimmedToFortyCharactersWithoutEndingOnADash() {
        String token = CampaignLinkFormat.postToken("A".repeat(60), 12345);

        assertThat(token).hasSizeLessThanOrEqualTo(40);
        assertThat(CampaignLinkFormat.isToken(token)).isTrue();
        assertThat(token).doesNotEndWith("-");
    }

    @Test
    void theAppStoreConnectConnectorAsksForAnOptionalNonSecretProviderToken() {
        var fields = new AppStoreConnectConnector().getSpec().fields();

        assertThat(fields).filteredOn(f -> f.key().equals("providerToken")).singleElement().satisfies(f -> {
            assertThat(f.required()).isFalse();
            assertThat(f.secret()).isFalse();
            assertThat(f.label()).containsIgnoringCase("provider token");
            assertThat(f.hint()).contains("Generate a Link");
        });
    }
}
