package com.conductor.v2.controller;

import com.conductor.creative.CreativeAttachService;
import com.conductor.creative.CreativeExperimentService;
import com.conductor.creative.CreativePerformanceService;
import com.conductor.creative.CreativePhoto;
import com.conductor.creative.CreativePhotoService;
import com.conductor.creative.CreativeRegistry;
import com.conductor.creative.CreativeRenderService;
import com.conductor.creative.CreativeService;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@code POST /marketing/photos}: {@code label}/{@code source}/{@code licence} are
 * bounded at 200 characters by the OpenAPI schema (their column width), so an over-long value is a 400 that
 * names the field rather than a database error. A standalone MockMvc over the real controller and advice —
 * the lightest context that still proves the bean-validation wiring of the generated interface.
 */
class CreativeControllerPhotoContractTest {

    private static final String URL = "/projects/proj-1/marketing/photos";

    private CreativePhotoService photoService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        photoService = Mockito.mock(CreativePhotoService.class);
        CreativeController controller = new CreativeController(photoService, Mockito.mock(CreativeService.class),
                Mockito.mock(CreativeRegistry.class), new ObjectMapper(), Mockito.mock(CreativeRenderService.class),
                Mockito.mock(CreativeAttachService.class), Mockito.mock(CreativePerformanceService.class),
                Mockito.mock(CreativeExperimentService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        User user = new User();
        user.setId("user-1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String body(String label) {
        return "{\"contentType\":\"image/jpeg\",\"sizeBytes\":1000,\"width\":1080,\"height\":1350,\"label\":\""
                + label + "\"}";
    }

    @Test
    void aLabelOver200CharactersIsRejectedWith400NamingTheField() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("x".repeat(201))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("label"));

        verify(photoService, never()).createPhoto(any(), any(), any());
    }

    @Test
    void aSourceOrLicenceOver200CharactersIsRejectedToo() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"sizeBytes\":1000,\"source\":\"" + "s".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("source"));
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"sizeBytes\":1000,\"licence\":\"" + "l".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("licence"));
    }

    @Test
    void aLabelWithAMiddleDotAndSlideNumbersIsCreated() throws Exception {
        String label = "Carousel 01 Save recipes · 4x5 · 01";
        CreativePhoto photo = new CreativePhoto();
        photo.setId("photo-1");
        photo.setProjectId("proj-1");
        photo.setContentType("image/jpeg");
        photo.setMediaKind(CreativePhoto.MEDIA_KIND_IMAGE);
        photo.setSizeBytes(1000L);
        photo.setLabel(label);
        photo.setUploadStatus(CreativePhoto.UPLOAD_STATUS_PENDING);
        photo.setFocal(new ObjectMapper().createObjectNode());
        photo.setCreatedAt(OffsetDateTime.now());
        when(photoService.createPhoto(eq("proj-1"), any(), any())).thenReturn(
                new CreativePhotoService.PhotoView(photo, null, "https://upload.example/put", null, List.of()));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(body(label)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("photo-1"));
    }
}
