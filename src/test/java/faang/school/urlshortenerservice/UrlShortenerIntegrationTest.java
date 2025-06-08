package faang.school.urlshortenerservice;

import com.redis.testcontainers.RedisContainer;
import faang.school.urlshortenerservice.dto.UrlShortenRequest;
import faang.school.urlshortenerservice.dto.UrlShortenResponse;
import faang.school.urlshortenerservice.entity.Hash;
import faang.school.urlshortenerservice.entity.HashStatus;
import faang.school.urlshortenerservice.entity.Url;
import faang.school.urlshortenerservice.repository.HashRepository;
import faang.school.urlshortenerservice.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("URL Shortener Service Integration Tests")
class UrlShortenerIntegrationTest {

    private static final String TEST_USER_ID = "123";
    private static final String ORIGINAL_URL = "https://example.com/very/long/url/path";
    private static final int INITIAL_SEQUENCE = 916132832;


    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private HashRepository hashRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private HttpHeaders headers;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Database configuration
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Redis configuration
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);

        // Application configuration
        registry.add("url.shortener.cache.capacity", () -> "100");
        registry.add("url.shortener.cache.refill-threshold", () -> "20");
        registry.add("url.shortener.hash.batch-size", () -> "50");
    }

    @BeforeEach
    void setUp() {
        initializeTestEnvironment();
        cleanupAndPrepareDatabase();
    }

    private void initializeTestEnvironment() {
        baseUrl = "http://localhost:" + port;
        headers = createDefaultHeaders();
    }

    private HttpHeaders createDefaultHeaders() {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        httpHeaders.set("X-USER-ID", TEST_USER_ID);
        return httpHeaders;
    }

    @Transactional
    protected void cleanupAndPrepareDatabase() {
        jdbcTemplate.execute("DELETE FROM url");
        jdbcTemplate.execute("DELETE FROM hash");
        jdbcTemplate.execute("ALTER SEQUENCE url_sequence RESTART WITH " + INITIAL_SEQUENCE);

        createTestHash();
    }

    private void createTestHash() {
        String[] testHashes = {
                "abc123", "abc124", "abc125", "abc126", "abc127", "abc128", "abc129", "abc130",
                "baaaaa", "baaaab", "baaaac", "baaaad", "baaaae", "baaaaf", "baaaag", "baaaah",
                "baabaa", "baabab", "baabac", "baabad", "baabae", "baabaf", "baabag", "baabah",
                "baacaa", "baacab", "baacac", "baacad", "baacae", "baacaf", "baacag", "baacah",
                "bab123", "bac123", "bad123", "bae123", "baf123", "bag123", "bah123", "bai123"
        };

        for (String hashValue : testHashes) {
            Hash hash = new Hash(hashValue);
            hash.setStatus(HashStatus.FREE);
            hashRepository.save(hash);
        }
    }

    @Nested
    @DisplayName("URL Shortening Tests")
    class UrlShorteningTests {

        @Test
        @DisplayName("Should successfully create short URL with valid input")
        void shouldCreateShortUrlSuccessfully() {
            UrlShortenRequest request = new UrlShortenRequest(ORIGINAL_URL);
            HttpEntity<UrlShortenRequest> requestEntity = new HttpEntity<>(request, headers);
            ResponseEntity<UrlShortenResponse> response = restTemplate.exchange(
                    baseUrl + "/url",
                    HttpMethod.POST,
                    requestEntity,
                    UrlShortenResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();

            String shortUrl = response.getBody().getShortUrl();
            assertThat(shortUrl).isNotBlank();

            String hashValue = extractHashFromShortUrl(shortUrl);
            verifyUrlWasSavedCorrectly(hashValue);
        }

        @Test
        @DisplayName("Should return bad request for invalid URL format")
        void shouldRejectInvalidUrlFormat() {
            String invalidUrl = "not-a-valid-url";
            UrlShortenRequest request = new UrlShortenRequest(invalidUrl);
            HttpEntity<UrlShortenRequest> requestEntity = new HttpEntity<>(request, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/url",
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains("Invalid URL format");
        }

        @Test
        @DisplayName("Should handle empty URL")
        void shouldRejectEmptyUrl() {
            UrlShortenRequest request = new UrlShortenRequest("");
            HttpEntity<UrlShortenRequest> requestEntity = new HttpEntity<>(request, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/url",
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("URL Redirection Tests")
    class UrlRedirectionTests {

        @Test
        @DisplayName("Should return not found for non-existent hash")
        void shouldReturnNotFoundForNonExistentHash() {
            String nonExistentHash = "xyz789";
            ResponseEntity<Void> response = restTemplate.exchange(
                    baseUrl + "/" + nonExistentHash,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Void.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("Should return bad request for invalid hash format")
        void shouldRejectInvalidHashFormat() {
            String invalidHash = "inv@lid#hash";
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/" + invalidHash,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains("Неверный формат хеша");
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle special characters in URL")
        void shouldHandleSpecialCharactersInUrl() {
            String urlWithSpecialChars = "https://example.com/path?param=value&other=test#fragment";
            UrlShortenRequest request = new UrlShortenRequest(urlWithSpecialChars);
            HttpEntity<UrlShortenRequest> requestEntity = new HttpEntity<>(request, headers);
            ResponseEntity<UrlShortenResponse> response = restTemplate.exchange(
                    baseUrl + "/url",
                    HttpMethod.POST,
                    requestEntity,
                    UrlShortenResponse.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();

            String hashValue = extractHashFromShortUrl(response.getBody().getShortUrl());
            Optional<Url> savedUrl = urlRepository.findByHashValue(hashValue);
            assertThat(savedUrl)
                    .isPresent()
                    .hasValueSatisfying(url ->
                            assertThat(url.getOriginalUrl()).isEqualTo(urlWithSpecialChars)
                    );
        }
    }

    private String extractHashFromShortUrl(String shortUrl) {
        return shortUrl.substring(shortUrl.lastIndexOf("/") + 1);
    }

    private void verifyUrlWasSavedCorrectly(String hashValue) {
        Optional<Url> savedUrl = urlRepository.findByHashValue(hashValue);
        assertThat(savedUrl)
                .isPresent()
                .hasValueSatisfying(url -> {
                    assertThat(url.getOriginalUrl()).isEqualTo(ORIGINAL_URL);
                    assertThat(url.getVisitsCount()).isEqualTo(0L);
                    assertThat(url.getHash()).isNotNull();
                });
    }
}