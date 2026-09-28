package com.ngumn.backend.controller;

import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.CommentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Deleting a comment. Comments are listed and added under their report:
 * /api/reports/{id}/comments and /api/complaints/{id}/comments.
 */
@RestController
@RequestMapping("/api/comments")
public class CommentController {

    private final CommentService commentService;
    private final AuthService authService;

    public CommentController(CommentService commentService, AuthService authService) {
        this.commentService = commentService;
        this.authService = authService;
    }

    /** Your own comment, or any comment on your own report. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader("Authorization") String authorization, @PathVariable Long id) {
        commentService.delete(authService.requireUser(authorization), id);
        return ResponseEntity.noContent().build();
    }
}
