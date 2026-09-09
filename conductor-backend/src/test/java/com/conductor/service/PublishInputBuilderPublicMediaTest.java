package com.conductor.service;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.service.publish.PublishPlatformRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The workspace's verified public media host rides along in the publish input, and only when set. */
class PublishInputBuilderPublicMediaTest {

    private final PublishTargetMediaResolver mediaResolver = mock(PublishTargetMediaResolver.class);
    private final ProjectSettingsRepository settingsRepository = mock(ProjectSettingsRepository.class);

    private static WorkItem post() {
        Project project = new Project();
        project.setId("proj-1");
        WorkItem post = new WorkItem();
        post.setId("wi-1");
        post.setProject(project);
        post.setTitle("t");
        return post;
    }

    private static PostPublishTarget tiktokTarget() {
        PostPublishTarget target = new PostPublishTarget();
        target.setId("t-1");
        target.setPlatform("tiktok");
        target.setConnectionId("conn-1");
        target.setLane(PublishLane.APP_MANAGED);
        return target;
    }

    @Test
    void theHostIsInTheInputWhenTheWorkspaceHasOne() {
        ProjectSettings settings = new ProjectSettings();
        settings.setPublicMediaBaseUrl("https://rexipe.io");
        when(settingsRepository.findByProjectId("proj-1")).thenReturn(Optional.of(settings));
        when(mediaResolver.effectiveMedia(any())).thenReturn(new PublishTargetMediaResolver.EffectiveMedia(List.of(), false));
        PublishInputBuilder builder = new PublishInputBuilder(new PublishPlatformRegistry(), mediaResolver, settingsRepository);

        Map<String, Object> input = builder.build(tiktokTarget(), post(), "caption");

        assertThat(input).containsEntry(PublishInputBuilder.INPUT_PUBLIC_MEDIA_BASE_URL, "https://rexipe.io");
    }

    @Test
    void theHostIsAbsentWithoutASettingOrWithoutTheRepository() {
        when(settingsRepository.findByProjectId("proj-1")).thenReturn(Optional.empty());
        when(mediaResolver.effectiveMedia(any())).thenReturn(new PublishTargetMediaResolver.EffectiveMedia(List.of(), false));

        assertThat(new PublishInputBuilder(new PublishPlatformRegistry(), mediaResolver, settingsRepository)
                .build(tiktokTarget(), post(), "caption")).doesNotContainKey(PublishInputBuilder.INPUT_PUBLIC_MEDIA_BASE_URL);
        assertThat(new PublishInputBuilder(new PublishPlatformRegistry(), mediaResolver)
                .build(tiktokTarget(), post(), "caption")).doesNotContainKey(PublishInputBuilder.INPUT_PUBLIC_MEDIA_BASE_URL);
    }
}
