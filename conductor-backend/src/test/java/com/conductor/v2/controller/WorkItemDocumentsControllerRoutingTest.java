package com.conductor.v2.controller;

import com.conductor.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A document is deleted by id (web, MCP) or by filename (sync daemon) on one URL shape. Two handlers on that
 * shape made Spring refuse every delete as ambiguous, which only a test through the request mapping can see.
 */
class WorkItemDocumentsControllerRoutingTest {

    private DocumentService documentService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        documentService = Mockito.mock(DocumentService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkItemDocumentsController(documentService)).build();
    }

    @Test
    void deleteByIdReachesTheService() throws Exception {
        mockMvc.perform(delete("/projects/p/work-items/w/documents/70d7193f-6357-49cb-aa7f-f3626feb195d"))
                .andExpect(status().isNoContent());

        verify(documentService).deleteDocument("p", "w", "70d7193f-6357-49cb-aa7f-f3626feb195d");
    }

    @Test
    void deleteByFilenameReachesTheService() throws Exception {
        mockMvc.perform(delete("/projects/p/work-items/w/documents/prd.md"))
                .andExpect(status().isNoContent());

        verify(documentService).deleteDocument("p", "w", "prd.md");
    }
}
