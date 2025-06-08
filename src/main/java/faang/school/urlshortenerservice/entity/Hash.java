package faang.school.urlshortenerservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;

@Slf4j
@Entity
@Getter
@Setter
@Table(name = "hash")
public class Hash {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 6)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HashStatus status = HashStatus.FREE;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    public Hash() {
    }

    public Hash(String value) {
        this.value = value;
    }
}