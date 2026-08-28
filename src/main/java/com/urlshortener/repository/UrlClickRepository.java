package com.urlshortener.repository;

import com.urlshortener.model.UrlClick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public interface UrlClickRepository extends JpaRepository<UrlClick, Long> {

    // url_Id uses Spring Data's underscore traversal: url -> id
    long countByUrl_Id(Long urlId);

    /** Returns the timestamp of the most recent click for the stats endpoint. */
    @Query("SELECT MAX(uc.clickedAt) FROM UrlClick uc WHERE uc.url.id = :urlId")
    Optional<OffsetDateTime> findLastClickedAtByUrlId(@Param("urlId") Long urlId);
}
