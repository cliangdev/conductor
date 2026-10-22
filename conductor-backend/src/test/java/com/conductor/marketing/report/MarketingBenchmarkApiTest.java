package com.conductor.marketing.report;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.MarketingBenchmarkRequest;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.conductor.v2.controller.MarketingReportController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Benchmark upsert, listing and permissions through the controller, against real Postgres. */
class MarketingBenchmarkApiTest extends AbstractNoneWebIntegrationTest {

    @Autowired private MarketingReportController controller;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private MarketingBenchmarkRepository benchmarkRepository;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private User user() {
        User u = new User();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Benchmark Tester");
        return userRepository.save(u);
    }

    private Project project(User owner) {
        Project p = new Project();
        p.setName("Benchmarks");
        p.setKey("BM" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(owner);
        return projectRepository.save(p);
    }

    private void member(Project p, User u, MemberRole role) {
        ProjectMember m = new ProjectMember();
        m.setProject(p);
        m.setUser(u);
        m.setRole(role);
        memberRepository.save(m);
    }

    private void actAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    private static MarketingBenchmarkRequest request(double value) {
        return new MarketingBenchmarkRequest(value, "Example Study", "https://example.com/study",
                LocalDate.now(ZoneOffset.UTC)).segment("Health, US");
    }

    @Test
    void anEditorUpsertsABenchmarkInPlaceAndEveryMemberCanReadIt() {
        User admin = user();
        User reviewer = user();
        Project p = project(admin);
        member(p, admin, MemberRole.ADMIN);
        member(p, reviewer, MemberRole.REVIEWER);

        actAs(admin);
        controller.setMarketingBenchmark(p.getId(), "store.conversion_rate", request(0.03));
        var second = controller.setMarketingBenchmark(p.getId(), "STORE.conversion_rate", request(0.04)).getBody();

        assertThat(second.getValue()).isEqualTo(0.04);
        assertThat(second.getSegment()).isEqualTo("Health, US");
        assertThat(benchmarkRepository.findByProjectIdOrderByMetricRefAsc(p.getId())).hasSize(1);

        actAs(reviewer);
        var listed = controller.listMarketingBenchmarks(p.getId()).getBody().getBenchmarks();
        assertThat(listed).extracting(b -> b.getMetricRef()).containsExactly("STORE.conversion_rate");
    }

    @Test
    void aReviewerCannotWriteOrDeleteABenchmark() {
        User admin = user();
        User reviewer = user();
        Project p = project(admin);
        member(p, admin, MemberRole.ADMIN);
        member(p, reviewer, MemberRole.REVIEWER);
        actAs(admin);
        controller.setMarketingBenchmark(p.getId(), "STORE.downloads", request(100));

        actAs(reviewer);
        assertThatThrownBy(() -> controller.setMarketingBenchmark(p.getId(), "STORE.downloads", request(1)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.deleteMarketingBenchmark(p.getId(), "STORE.downloads"))
                .isInstanceOf(ForbiddenException.class);
        assertThat(benchmarkRepository.findByProjectIdAndMetricRef(p.getId(), "STORE.downloads")).isPresent();
    }

    @Test
    void aNonMemberCannotReadTheBenchmarksOrTheReport() {
        User admin = user();
        User outsider = user();
        Project p = project(admin);
        member(p, admin, MemberRole.ADMIN);

        actAs(outsider);
        assertThatThrownBy(() -> controller.listMarketingBenchmarks(p.getId())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.getMarketingReport(p.getId(), "28d")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void deletingIsIdempotent() {
        User admin = user();
        Project p = project(admin);
        member(p, admin, MemberRole.ADMIN);
        actAs(admin);
        controller.setMarketingBenchmark(p.getId(), "ADS.tap_through_rate", request(0.07));

        assertThat(controller.deleteMarketingBenchmark(p.getId(), "ADS.tap_through_rate").getStatusCode().value())
                .isEqualTo(204);
        assertThat(controller.deleteMarketingBenchmark(p.getId(), "ADS.tap_through_rate").getStatusCode().value())
                .isEqualTo(204);
        assertThat(benchmarkRepository.findByProjectIdOrderByMetricRefAsc(p.getId())).isEmpty();
    }

    @Test
    void anInvalidBenchmarkIsRejectedBeforeAnythingIsStored() {
        User admin = user();
        Project p = project(admin);
        member(p, admin, MemberRole.ADMIN);
        actAs(admin);
        MarketingBenchmarkRequest future = new MarketingBenchmarkRequest(1.0, "Study", "https://example.com",
                LocalDate.now(ZoneOffset.UTC).plusDays(3));

        assertThatThrownBy(() -> controller.setMarketingBenchmark(p.getId(), "STORE.downloads", future))
                .isInstanceOf(com.conductor.exception.BusinessException.class);
        assertThat(benchmarkRepository.findByProjectIdOrderByMetricRefAsc(p.getId())).isEmpty();
    }
}
