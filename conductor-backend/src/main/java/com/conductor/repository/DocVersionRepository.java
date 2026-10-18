package com.conductor.repository;

import com.conductor.entity.DocVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DocVersionRepository extends JpaRepository<DocVersion, String> {

    // LEFT JOIN FETCH on the author: a version written by an agent has none, and an inner join would
    // hide it from the history panel entirely.
    @Query("SELECT v FROM DocVersion v LEFT JOIN FETCH v.author WHERE v.doc.id = :docId ORDER BY v.versionNumber DESC")
    List<DocVersion> findByDocIdOrderByVersionNumberDesc(@Param("docId") String docId);

    /** A version without its {@code content}: the history panel lists many and shows none of them. */
    interface Summary {
        String getId();
        String getDocId();
        int getVersionNumber();
        String getAuthorId();
        String getAuthorLabel();
        String getAuthorDisplayName();
        String getAuthorName();
        String getAuthorEmail();
        OffsetDateTime getCreatedAt();
    }

    @Query("""
            SELECT v.id AS id, v.doc.id AS docId, v.versionNumber AS versionNumber, a.id AS authorId,
                   v.authorLabel AS authorLabel, a.displayName AS authorDisplayName, a.name AS authorName,
                   a.email AS authorEmail, v.createdAt AS createdAt
            FROM DocVersion v LEFT JOIN v.author a
            WHERE v.doc.id = :docId ORDER BY v.versionNumber DESC""")
    List<Summary> findSummariesByDocId(@Param("docId") String docId);

    @Query("SELECT v FROM DocVersion v LEFT JOIN FETCH v.author JOIN FETCH v.doc WHERE v.id = :id")
    Optional<DocVersion> findByIdWithAuthor(@Param("id") String id);

    int countByDocId(String docId);

    Optional<DocVersion> findTopByDocIdOrderByVersionNumberDesc(String docId);
}
