package faang.school.urlshortenerservice.repository;

import faang.school.urlshortenerservice.entity.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {
    @Query("SELECT u FROM Url u JOIN FETCH u.hash h WHERE h.value = :hashValue")
    Optional<Url> findByHashValue(String hashValue);

    @Modifying
    @Query(value = """
            DELETE FROM url 
            WHERE expires_at < :currentTime 
            RETURNING hash_value
            """, nativeQuery = true)
    List<String> deleteExpiredAndReturnHashes(LocalDateTime currentTime);

    @Query("SELECT u FROM Url u WHERE u.originalUrl = :originalUrl")
    Optional<Url> findByOriginalUrl(@Param("originalUrl") String originalUrl);
}