package faang.school.urlshortenerservice.repository;

import faang.school.urlshortenerservice.entity.Hash;
import faang.school.urlshortenerservice.entity.HashStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface HashRepository extends JpaRepository<Hash, Long> {

    @Query(value = "SELECT nextval('url_sequence') FROM generate_series(1, :range)", nativeQuery = true)
    List<Long> getNextSequenceValues(@Param("range") int range);

    @Query(value = "SELECT h.value FROM Hash h WHERE h.status IN ('FREE') ORDER BY h.id LIMIT :limit", nativeQuery = true)
    List<String> findAvailableHashes(@Param("limit") int limit);

    @Modifying
    @Query(value = "UPDATE hash SET status = 'RESERVED' WHERE value IN (:values) AND status = 'FREE'", nativeQuery = true)
    int markAsReserved(@Param("values") List<String> values);

    @Modifying
    @Query(value = "UPDATE hash SET status = 'USED' WHERE value = :value AND status = 'RESERVED'", nativeQuery = true)
    int markAsUsed(@Param("value") String value);

    @Modifying
    @Query(value = """
            INSERT INTO hash (value, status)
            VALUES (:hash, 'FREE')
            ON CONFLICT (value) DO UPDATE 
            SET status = 'FREE'
            """, nativeQuery = true)
    @Transactional
    void saveHash(@Param("hash") String hash);

    @Modifying
    @Query(value = """
            INSERT INTO hash (value, status)
            SELECT unnest(:hashes) AS value, 'FREE'
            ON CONFLICT (value) DO UPDATE 
            SET status = 'FREE'
            """, nativeQuery = true)
    @Transactional
    void saveHashBatch(@Param("hashes") List<String> hashes);

    Optional<Hash> findByValue(String value);

    @Query("SELECT COUNT(h) FROM Hash h WHERE h.status IN :statuses")
    long countByStatusIn(@Param("statuses") List<HashStatus> statuses);

    @Query("SELECT COUNT(h) FROM Hash h")
    long countExistingHashes();
}