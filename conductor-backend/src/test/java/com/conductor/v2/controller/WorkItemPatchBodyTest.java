package com.conductor.v2.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ReviewerService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.workflow.RunTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Raw-JSON PATCH bodies against the real generated DTO: what the service receives for a field the body
 * omitted must be null ("leave alone"), distinct from an explicit empty value ("clear"). The generated
 * model once defaulted omitted {@code tags}/{@code fields} to empty containers, which wiped tags on every
 * title-only PATCH.
 */
@WebMvcTest(WorkItemController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class WorkItemPatchBodyTest {

    private static final String URL = "/api/v2/projects/proj-1/work-items/wi-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkItemService workItemService;
    @MockitoBean
    private WorkItemWorkflowService workItemWorkflowService;
    @MockitoBean
    private ReviewerService reviewerService;
    @MockitoBean
    private WorkItemListEnricher listEnricher;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean
    private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean
    private RunTokenService runTokenService;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId("user-1");
        user.setEmail("u@example.com");
        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        Project project = new Project();
        project.setId("proj-1");
        project.setKey("COND");
        WorkItem item = new WorkItem();
        item.setId("wi-1");
        item.setProject(project);
        item.setType("PRD");
        item.setTitle("T");
        item.setCurrentStatus("DRAFT");
        item.setSequenceNumber(1);
        item.setCreatedAt(OffsetDateTime.now());
        item.setUpdatedAt(OffsetDateTime.now());
        when(workItemService.patchWorkItem(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(item);
    }

    private record Sent(Collection<String> tags, Map<String, Object> fields, String dueOn, String title) {}

    @SuppressWarnings("unchecked")
    private Sent send(String json) throws Exception {
        mockMvc.perform(patch(URL).header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
        ArgumentCaptor<Collection<String>> tags = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> dueOn = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(workItemService).patchWorkItem(eq("proj-1"), eq("wi-1"), title.capture(), any(), any(), any(),
                any(), any(), tags.capture(), any(), fields.capture(), dueOn.capture(), any(User.class));
        return new Sent(tags.getValue(), fields.getValue(), dueOn.getValue(), title.getValue());
    }

    @Test
    void titleOnlyPatchLeavesTagsAndFieldsUntouched() throws Exception {
        Sent sent = send("{\"title\":\"New\"}");
        assertThat(sent.title()).isEqualTo("New");
        assertThat(sent.tags()).isNull();
        assertThat(sent.fields()).isNull();
        assertThat(sent.dueOn()).isNull();
    }

    @Test
    void statusOnlyPatchLeavesTagsAndFieldsUntouched() throws Exception {
        Sent sent = send("{\"status\":\"IN_PROGRESS\"}");
        assertThat(sent.tags()).isNull();
        assertThat(sent.fields()).isNull();
    }

    @Test
    void emptyTagsArrayIsSentAsEmptyToClear() throws Exception {
        assertThat(send("{\"tags\":[]}").tags()).isNotNull().isEmpty();
    }

    @Test
    void tagsArraySetsTheList() throws Exception {
        assertThat(send("{\"tags\":[\"a\"]}").tags()).isEqualTo(List.of("a"));
    }

    @Test
    void emptyFieldsObjectIsSentAsEmptyNotNull() throws Exception {
        assertThat(send("{\"fields\":{}}").fields()).isNotNull().isEmpty();
    }

    @Test
    void fieldsObjectIsPassedThrough() throws Exception {
        assertThat(send("{\"fields\":{\"k\":\"v\"}}").fields()).containsEntry("k", "v");
    }

    @Test
    void emptyDueOnIsSentAsEmptyToClear() throws Exception {
        assertThat(send("{\"dueOn\":\"\"}").dueOn()).isEmpty();
    }
}
