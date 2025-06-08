package faang.school.urlshortenerservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("URL Visit Service Tests")
class UrlVisitServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private UrlVisitService urlVisitService;

    private ConcurrentHashMap<String, AtomicInteger> visitsBuffer;

    @BeforeEach
    void setUp() {
        visitsBuffer = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(urlVisitService, "visitsBuffer", visitsBuffer);
    }

    @Test
    @DisplayName("Should register single visit for new hash value")
    void shouldRegisterSingleVisitForNewHashValue() {
        String hashValue = "abc123";
        urlVisitService.registerVisit(hashValue);
        assertEquals(1, visitsBuffer.get(hashValue).get());
        assertEquals(1, visitsBuffer.size());
    }

    @Test
    @DisplayName("Should increment visit count for existing hash value")
    void shouldIncrementVisitCountForExistingHashValue() {
        String hashValue = "abc123";
        urlVisitService.registerVisit(hashValue);
        urlVisitService.registerVisit(hashValue);
        urlVisitService.registerVisit(hashValue);
        assertEquals(3, visitsBuffer.get(hashValue).get());
        assertEquals(1, visitsBuffer.size());
    }

    @Test
    @DisplayName("Should register visits for multiple different hash values")
    void shouldRegisterVisitsForMultipleDifferentHashValues() {
        String hashValue1 = "abc123";
        String hashValue2 = "def456";
        String hashValue3 = "ghi789";
        urlVisitService.registerVisit(hashValue1);
        urlVisitService.registerVisit(hashValue2);
        urlVisitService.registerVisit(hashValue1);
        urlVisitService.registerVisit(hashValue3);
        assertEquals(2, visitsBuffer.get(hashValue1).get());
        assertEquals(1, visitsBuffer.get(hashValue2).get());
        assertEquals(1, visitsBuffer.get(hashValue3).get());
        assertEquals(3, visitsBuffer.size());
    }

    @Test
    @DisplayName("Should handle concurrent visits registration safely")
    void shouldHandleConcurrentVisitsRegistrationSafely() throws InterruptedException {
        String hashValue = "concurrent123";
        int numberOfThreads = 10;
        int visitsPerThread = 100;
        Thread[] threads = new Thread[numberOfThreads];
        for (int i = 0; i < numberOfThreads; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < visitsPerThread; j++) {
                    urlVisitService.registerVisit(hashValue);
                }
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(numberOfThreads * visitsPerThread, visitsBuffer.get(hashValue).get());
    }

    @Test
    @DisplayName("Should not flush when visits buffer is empty")
    void shouldNotFlushWhenVisitsBufferIsEmpty() {
        visitsBuffer.clear();
        urlVisitService.flushVisitsBatch();
        verify(jdbcTemplate, never()).batchUpdate(any(String.class), any(List.class));
    }

    @Test
    @DisplayName("Should flush visits batch and update database correctly")
    void shouldFlushVisitsBatchAndUpdateDatabaseCorrectly() {
        visitsBuffer.put("hash1", new AtomicInteger(5));
        visitsBuffer.put("hash2", new AtomicInteger(3));
        visitsBuffer.put("hash3", new AtomicInteger(7));

        when(jdbcTemplate.batchUpdate(any(String.class), any(List.class)))
                .thenReturn(new int[]{1, 1, 1});
        urlVisitService.flushVisitsBatch();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), paramsCaptor.capture());
        String expectedSql = "UPDATE url SET visits_count = visits_count + ? WHERE hash_value = ?";
        assertEquals(expectedSql, sqlCaptor.getValue());
        List<Object[]> batchParams = paramsCaptor.getValue();
        assertEquals(3, batchParams.size());
        assertEquals(0, visitsBuffer.get("hash1").get());
        assertEquals(0, visitsBuffer.get("hash2").get());
        assertEquals(0, visitsBuffer.get("hash3").get());
    }

    @Test
    @DisplayName("Should flush only entries with positive visit counts")
    void shouldFlushOnlyEntriesWithPositiveVisitCounts() {
        visitsBuffer.put("hash1", new AtomicInteger(5));
        visitsBuffer.put("hash2", new AtomicInteger(0));
        visitsBuffer.put("hash3", new AtomicInteger(3));
        when(jdbcTemplate.batchUpdate(any(String.class), any(List.class)))
                .thenReturn(new int[]{1, 1});
        urlVisitService.flushVisitsBatch();
        ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate).batchUpdate(any(String.class), paramsCaptor.capture());
        List<Object[]> batchParams = paramsCaptor.getValue();
        assertEquals(2, batchParams.size());
        boolean foundHash1 = false, foundHash3 = false;
        for (Object[] params : batchParams) {
            int count = (Integer) params[0];
            String hash = (String) params[1];
            assertTrue(count > 0, "Count should be positive");
            if ("hash1".equals(hash)) {
                assertEquals(5, count);
                foundHash1 = true;
            } else if ("hash3".equals(hash)) {
                assertEquals(3, count);
                foundHash3 = true;
            }
        }
        assertTrue(foundHash1, "hash1 should be included");
        assertTrue(foundHash3, "hash3 should be included");
    }

    @Test
    @DisplayName("Should reset visit counters after successful batch flush")
    void shouldResetVisitCountersAfterSuccessfulBatchFlush() {
        String hashValue = "test123";
        visitsBuffer.put(hashValue, new AtomicInteger(10));
        when(jdbcTemplate.batchUpdate(any(String.class), any(List.class)))
                .thenReturn(new int[]{1});
        urlVisitService.flushVisitsBatch();
        assertEquals(0, visitsBuffer.get(hashValue).get());
    }

    @Test
    @DisplayName("Should handle batch update with mixed success results")
    void shouldHandleBatchUpdateWithMixedSuccessResults() {
        visitsBuffer.put("hash1", new AtomicInteger(2));
        visitsBuffer.put("hash2", new AtomicInteger(4));
        visitsBuffer.put("hash3", new AtomicInteger(6));
        when(jdbcTemplate.batchUpdate(any(String.class), any(List.class)))
                .thenReturn(new int[]{1, 0, 1});
        urlVisitService.flushVisitsBatch();
        verify(jdbcTemplate).batchUpdate(any(String.class), any(List.class));
        assertEquals(0, visitsBuffer.get("hash1").get());
        assertEquals(0, visitsBuffer.get("hash2").get());
        assertEquals(0, visitsBuffer.get("hash3").get());
    }

    @Test
    @DisplayName("Should maintain buffer integrity after flush operation")
    void shouldMaintainBufferIntegrityAfterFlushOperation() {
        visitsBuffer.put("existing", new AtomicInteger(5));
        when(jdbcTemplate.batchUpdate(any(String.class), any(List.class)))
                .thenReturn(new int[]{1});
        urlVisitService.flushVisitsBatch();
        urlVisitService.registerVisit("existing");
        urlVisitService.registerVisit("new");
        assertEquals(1, visitsBuffer.get("existing").get());
        assertEquals(1, visitsBuffer.get("new").get());
        assertEquals(2, visitsBuffer.size());
    }

    @Test
    @DisplayName("Should handle database exception during batch flush gracefully")
    void shouldHandleDatabaseExceptionDuringBatchFlushGracefully() {
        visitsBuffer.put("hash1", new AtomicInteger(3));
        when(jdbcTemplate.batchUpdate(any(String.class), any(List.class)))
                .thenThrow(new RuntimeException("Database connection failed"));
        assertThrows(RuntimeException.class, () -> urlVisitService.flushVisitsBatch());
        assertEquals(0, visitsBuffer.get("hash1").get());
    }

    @Test
    @DisplayName("Should use correct SQL query for batch update")
    void shouldUseCorrectSqlQueryForBatchUpdate() {
        visitsBuffer.put("test", new AtomicInteger(1));
        when(jdbcTemplate.batchUpdate(any(String.class), any(List.class)))
                .thenReturn(new int[]{1});
        urlVisitService.flushVisitsBatch();
        String expectedSql = "UPDATE url SET visits_count = visits_count + ? WHERE hash_value = ?";
        verify(jdbcTemplate).batchUpdate(eq(expectedSql), any(List.class));
    }

    @Test
    @DisplayName("Should ignore null hash value registration")
    void shouldIgnoreNullHashValueRegistration() {
        assertDoesNotThrow(() -> urlVisitService.registerVisit(null));
        assertTrue(visitsBuffer.isEmpty());
    }

    @Test
    @DisplayName("Should ignore empty string hash value registration")
    void shouldIgnoreEmptyStringHashValueRegistration() {
        assertDoesNotThrow(() -> {
            urlVisitService.registerVisit("");
            urlVisitService.registerVisit("   ");
            urlVisitService.registerVisit("\t");
        });
        assertTrue(visitsBuffer.isEmpty());
    }

    @Test
    @DisplayName("Should process valid hash values correctly after ignoring invalid ones")
    void shouldProcessValidHashValuesAfterIgnoringInvalidOnes() {
        String validHash = "abc123";
        urlVisitService.registerVisit(null);
        urlVisitService.registerVisit("");
        urlVisitService.registerVisit("   ");
        urlVisitService.registerVisit(validHash);
        urlVisitService.registerVisit(validHash);
        assertEquals(1, visitsBuffer.size());
        assertTrue(visitsBuffer.containsKey(validHash));
        assertEquals(2, visitsBuffer.get(validHash).get());
    }

    @ParameterizedTest
    @DisplayName("Should ignore various invalid hash values")
    @ValueSource(strings = {"", " ", "  ", "\t", "\n", "\r", "\t\n\r"})
    @NullSource
    void shouldIgnoreInvalidHashValues(String invalidHash) {
        int initialSize = visitsBuffer.size();
        assertDoesNotThrow(() -> urlVisitService.registerVisit(invalidHash));
        assertEquals(initialSize, visitsBuffer.size());
    }

    @ParameterizedTest
    @DisplayName("Should process valid hash values correctly")
    @ValueSource(strings = {"abc123", "def456", "a", "1", "hash-with-dash", "hash_with_underscore", "MixedCase123"})
    void shouldProcessValidHashValues(String validHash) {
        urlVisitService.registerVisit(validHash);
        assertTrue(visitsBuffer.containsKey(validHash));
        assertEquals(1, visitsBuffer.get(validHash).get());
    }
}