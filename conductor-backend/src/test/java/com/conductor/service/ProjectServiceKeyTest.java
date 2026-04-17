package com.conductor.service;

import com.conductor.exception.BusinessException;
import com.conductor.repository.OrgMemberRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.TeamMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceKeyTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @Mock
    private OrgMemberRepository orgMemberRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(projectRepository, projectMemberRepository, projectSecurityService, orgMemberRepository, teamMemberRepository);
    }

    @Test
    void deriveKeyTruncatesSingleWordToFourChars() {
        assertThat(projectService.deriveKey("Conductor")).isEqualTo("COND");
    }

    @Test
    void deriveKeyUsesInitialsForMultiWordName() {
        assertThat(projectService.deriveKey("My Engineering App")).isEqualTo("MEA");
    }

    @Test
    void deriveKeyPreservesShortTwoCharName() {
        assertThat(projectService.deriveKey("AB")).isEqualTo("AB");
    }

    @Test
    void deriveKeyPadsSingleCharNameToTwoChars() {
        assertThat(projectService.deriveKey("A")).isEqualTo("AX");
    }

    @Test
    void deriveKeyStripsSpecialCharsBeforeDerivation() {
        assertThat(projectService.deriveKey("My-Cool App!")).isEqualTo("MA");
    }

    @Test
    void deriveKeyCapsInitialsAtSix() {
        assertThat(projectService.deriveKey("Alpha Bravo Charlie Delta Echo Foxtrot Golf"))
            .isEqualTo("ABCDEF");
    }

    @Test
    void resolveUniqueKeyReturnsBaseWhenNoConflict() {
        when(projectRepository.existsByKey("COND")).thenReturn(false);
        assertThat(projectService.resolveUniqueKey("Conductor")).isEqualTo("COND");
    }

    @Test
    void resolveUniqueKeyAppendsNumberOnConflict() {
        when(projectRepository.existsByKey("COND")).thenReturn(true);
        when(projectRepository.existsByKey("COND1")).thenReturn(false);
        assertThat(projectService.resolveUniqueKey("Conductor")).isEqualTo("COND1");
    }

    @Test
    void resolveUniqueKeyThrowsWhenAllVariantsExhausted() {
        when(projectRepository.existsByKey(anyString())).thenReturn(true);
        assertThatThrownBy(() -> projectService.resolveUniqueKey("Conductor"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Cannot derive unique key for project");
    }
}
