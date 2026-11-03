package com.conductor.v2.controller;

import com.conductor.creative.CreativeEditReadService;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.api.CreativeEditsApi;
import com.conductor.generated.v2.model.CreativeAffectedPost;
import com.conductor.generated.v2.model.CreativeAffectedPostsResponse;
import com.conductor.generated.v2.model.CreativeRenderAvailability;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * What editing a slideshow Creative's text would do before anyone saves: which Posts it moves and where each goes,
 * and where the re-render would run. Read-only; {@link CreativeEditReadService} owns the membership check.
 */
@RestController
public class CreativeEditsController implements CreativeEditsApi {

    private final CreativeEditReadService readService;

    public CreativeEditsController(CreativeEditReadService readService) {
        this.readService = readService;
    }

    @Override
    public ResponseEntity<CreativeAffectedPostsResponse> listCreativeAffectedPosts(String projectId, String creativeId,
                                                                                   String postId) {
        return ResponseEntity.ok(new CreativeAffectedPostsResponse(
                readService.affectedPosts(projectId, creativeId, postId, currentUser()).stream()
                        .map(p -> new CreativeAffectedPost(p.workItemId(), p.displayId(), p.title(), p.status(),
                                p.statusLabel(), CreativeAffectedPost.OutcomeEnum.fromValue(p.outcome())))
                        .toList()));
    }

    @Override
    public ResponseEntity<CreativeRenderAvailability> getCreativeRenderAvailability(String projectId, String creativeId) {
        CreativeEditReadService.RenderAvailability a = readService.renderAvailability(projectId, creativeId, currentUser());
        return ResponseEntity.ok(new CreativeRenderAvailability(
                CreativeRenderAvailability.RuntimeEnum.fromValue(a.runtime()))
                .targetName(a.targetName()).daemonOnline(a.daemonOnline()));
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user;
        }
        throw new ForbiddenException("This endpoint needs a signed-in user or a user API key");
    }
}
