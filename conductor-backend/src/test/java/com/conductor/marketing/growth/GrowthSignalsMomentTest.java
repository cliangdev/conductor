package com.conductor.marketing.growth;

import com.conductor.marketing.growth.MomentEvaluator.MomentView;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Moment status and surface warnings are computed on read from UTC today; nothing is stored. */
class GrowthSignalsMomentTest {

    private static final LocalDate THANKSGIVING = LocalDate.of(2026, 11, 26);
    private static final LocalDate DUE = LocalDate.of(2026, 11, 12);

    private static MarketingMoment thanksgiving(int leadDays) {
        MarketingMoment m = new MarketingMoment();
        m.setMomentKey("thanksgiving-2026");
        m.setName("Thanksgiving");
        m.setMomentDate(THANKSGIVING);
        m.setLeadDays(leadDays);
        return m;
    }

    private static MarketingMomentSurface surface(String id, String name, LocalDate dueOn, String changeId) {
        MarketingMomentSurface s = new MarketingMomentSurface();
        s.setId(id);
        s.setSurface(name);
        s.setDueOn(dueOn);
        s.setChangeWorkItemId(changeId);
        return s;
    }

    private static MomentView evaluate(LocalDate today, MarketingMomentSurface... surfaces) {
        return MomentEvaluator.evaluate(thanksgiving(21), List.of(surfaces), Map.of("wi-1", "RE-7"), today);
    }

    @Test
    void aSurfacePastDueWithNoChangeWarnsOnTheDayAfterItsDueDate() {
        MomentView view = evaluate(LocalDate.of(2026, 11, 13),
                surface("s1", "App Store promotional text", DUE, null));

        assertThat(view.status()).isEqualTo("warning");
        assertThat(view.surfaces().get(0).warning())
                .isEqualTo("App Store promotional text for Thanksgiving was due 2026-11-12 and no change is recorded");
    }

    @Test
    void aSurfaceDueTodayDoesNotWarnYet() {
        MomentView view = evaluate(DUE, surface("s1", "App Store promotional text", DUE, null));

        assertThat(view.surfaces().get(0).warning()).isNull();
        assertThat(view.status()).isNotEqualTo("warning");
    }

    @Test
    void aLinkedChangeClearsTheWarningAndShowsItsDisplayId() {
        MomentView view = evaluate(LocalDate.of(2026, 11, 20),
                surface("s1", "App Store promotional text", DUE, "wi-1"));

        assertThat(view.surfaces().get(0).warning()).isNull();
        assertThat(view.surfaces().get(0).changeDisplayId()).isEqualTo("RE-7");
        assertThat(view.status()).isNotEqualTo("warning");
    }

    @Test
    void oneOverdueSurfaceWarnsEvenWhenAnotherHasItsChange() {
        MomentView view = evaluate(LocalDate.of(2026, 11, 20),
                surface("s1", "App Store promotional text", DUE, "wi-1"),
                surface("s2", "TikTok post", LocalDate.of(2026, 11, 14), null));

        assertThat(view.status()).isEqualTo("warning");
        assertThat(view.surfaces()).extracting(MomentEvaluator.SurfaceView::warning)
                .containsExactly(null, "TikTok post for Thanksgiving was due 2026-11-14 and no change is recorded");
    }

    @Test
    void aMomentInsideItsLeadWindowWithASurfaceNotYetDueIsUpcoming() {
        // 21 days before 2026-11-26 is 2026-11-05
        assertThat(evaluate(LocalDate.of(2026, 11, 5), surface("s1", "Email", DUE, null)).status()).isEqualTo("upcoming");
        assertThat(evaluate(DUE, surface("s1", "Email", DUE, null)).status()).isEqualTo("upcoming");
    }

    @Test
    void aMomentOutsideItsLeadWindowIsOnlyScheduled() {
        assertThat(evaluate(LocalDate.of(2026, 11, 4), surface("s1", "Email", DUE, null)).status()).isEqualTo("scheduled");
    }

    @Test
    void aMomentWhoseSurfacesAreAllHandledIsNotUpcomingAndNotWarning() {
        MomentView view = evaluate(LocalDate.of(2026, 11, 20), surface("s1", "Email", DUE, "wi-1"));

        assertThat(view.status()).isEqualTo("scheduled");
    }

    @Test
    void aMomentWithoutSurfacesIsNeverUpcoming() {
        assertThat(evaluate(LocalDate.of(2026, 11, 20)).status()).isEqualTo("scheduled");
    }

    @Test
    void aMomentThatHasGoneByWithEverythingHandledIsPast() {
        MomentView view = evaluate(LocalDate.of(2026, 11, 27), surface("s1", "Email", DUE, "wi-1"));

        assertThat(view.status()).isEqualTo("past");
    }

    @Test
    void aPastMomentWithAnUnhandledSurfaceStillWarns() {
        MomentView view = evaluate(LocalDate.of(2026, 11, 27), surface("s1", "Email", DUE, null));

        assertThat(view.status()).isEqualTo("warning");
    }

    @Test
    void theViewCarriesTheKeyNameDateAndLeadDays() {
        MomentView view = evaluate(LocalDate.of(2026, 11, 4), surface("s1", "Email", DUE, null));

        assertThat(view.key()).isEqualTo("thanksgiving-2026");
        assertThat(view.name()).isEqualTo("Thanksgiving");
        assertThat(view.date()).isEqualTo(THANKSGIVING);
        assertThat(view.leadDays()).isEqualTo(21);
        assertThat(view.surfaces().get(0).id()).isEqualTo("s1");
        assertThat(view.surfaces().get(0).dueOn()).isEqualTo(DUE);
    }
}
