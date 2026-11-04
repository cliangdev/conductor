package com.conductor.v2.controller;

import com.conductor.creative.CreativeEditReadService;
import com.conductor.creative.PostContentChange;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.api.WorkItemContentChangesApi;
import com.conductor.generated.v2.model.CreativeRevisionTextChange;
import com.conductor.generated.v2.model.PostContentChangeListResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** The Creative edits that reached a Post, newest first ({@code GET .../work-items/{id}/content-changes}). */
@RestController
public class WorkItemContentChangesController implements WorkItemContentChangesApi {

    private final CreativeEditReadService readService;

    public WorkItemContentChangesController(CreativeEditReadService readService) {
        this.readService = readService;
    }

    @Override
    public ResponseEntity<PostContentChangeListResponse> listWorkItemContentChanges(String projectId, String workItemId) {
        List<com.conductor.generated.v2.model.PostContentChange> changes =
                readService.contentChanges(projectId, workItemId, currentUser()).stream().map(this::toResponse).toList();
        return ResponseEntity.ok(new PostContentChangeListResponse(changes));
    }

    private com.conductor.generated.v2.model.PostContentChange toResponse(CreativeEditReadService.ChangeView view) {
        PostContentChange c = view.change();
        List<CreativeRevisionTextChange> diff = new ArrayList<>();
        if (c.getTextDiff() != null) {
            c.getTextDiff().forEach(n -> diff.add(new CreativeRevisionTextChange(n.get("slide").asInt(), n.get("path").asText())
                    .old(n.hasNonNull("old") ? n.get("old").asText() : null)
                    ._new(n.hasNonNull("new") ? n.get("new").asText() : null)));
        }
        return new com.conductor.generated.v2.model.PostContentChange(c.getId(), c.getCreativeId(), c.getRevisionId(),
                view.revisionNo(),
                com.conductor.generated.v2.model.PostContentChange.SourceEnum.fromValue(c.getSource()),
                c.getReviewRoundBefore(), c.getReviewRoundAfter(), diff, c.getCreatedAt())
                .creativeDisplayId(view.creativeDisplayId())
                .actorUserId(c.getActorUserId())
                .actorName(view.actorName())
                .fromStatus(c.getFromStatus())
                .toStatus(c.getToStatus());
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user;
        }
        throw new ForbiddenException("This endpoint needs a signed-in user or a user API key");
    }
}
