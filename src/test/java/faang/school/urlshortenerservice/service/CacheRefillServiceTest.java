package faang.school.urlshortenerservice.service;

import faang.school.urlshortenerservice.generator.HashGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Cache Refill Service Tests")
class CacheRefillServiceTest {

    @Mock
    private HashGenerator hashGenerator;

    @InjectMocks
    private CacheRefillService cacheRefillService;

    @Test
    @DisplayName("Should return empty list when space available is zero")
    void shouldReturnEmptyListWhenSpaceAvailableIsZero() {
        int spaceAvailable = 0;
        List<String> result = cacheRefillService.refillCache(spaceAvailable);
        assertTrue(result.isEmpty());
        verifyNoInteractions(hashGenerator);
    }

    @Test
    @DisplayName("Should return empty list when space available is negative")
    void shouldReturnEmptyListWhenSpaceAvailableIsNegative() {
        int spaceAvailable = -5;
        List<String> result = cacheRefillService.refillCache(spaceAvailable);
        assertTrue(result.isEmpty());
        verifyNoInteractions(hashGenerator);
    }

    @Test
    @DisplayName("Should successfully refill cache with available hashes when space is positive")
    void shouldSuccessfullyRefillCacheWhenSpaceIsPositive() {
        int spaceAvailable = 10;
        List<String> expectedHashes = Arrays.asList("hash1", "hash2", "hash3");
        when(hashGenerator.getAvailableHashes(spaceAvailable)).thenReturn(expectedHashes);
        List<String> result = cacheRefillService.refillCache(spaceAvailable);
        assertEquals(expectedHashes, result);
        verify(hashGenerator).getAvailableHashes(spaceAvailable);
    }

    @Test
    @DisplayName("Should return empty list when hash generator returns empty list")
    void shouldReturnEmptyListWhenHashGeneratorReturnsEmptyList() {
        int spaceAvailable = 5;
        when(hashGenerator.getAvailableHashes(spaceAvailable)).thenReturn(Collections.emptyList());
        List<String> result = cacheRefillService.refillCache(spaceAvailable);
        assertTrue(result.isEmpty());
        verify(hashGenerator).getAvailableHashes(spaceAvailable);
    }

    @Test
    @DisplayName("Should handle large space available values correctly")
    void shouldHandleLargeSpaceAvailableValuesCorrectly() {
        int spaceAvailable = Integer.MAX_VALUE;
        List<String> expectedHashes = Arrays.asList("hash1", "hash2");
        when(hashGenerator.getAvailableHashes(spaceAvailable)).thenReturn(expectedHashes);
        List<String> result = cacheRefillService.refillCache(spaceAvailable);
        assertEquals(expectedHashes, result);
        verify(hashGenerator).getAvailableHashes(spaceAvailable);
    }

    @Test
    @DisplayName("Should propagate exception from hash generator during cache refill")
    void shouldPropagateExceptionFromHashGeneratorDuringCacheRefill() {
        int spaceAvailable = 10;
        RuntimeException expectedException = new RuntimeException("Hash generation failed");
        when(hashGenerator.getAvailableHashes(spaceAvailable)).thenThrow(expectedException);
        RuntimeException actualException = assertThrows(RuntimeException.class,
                () -> cacheRefillService.refillCache(spaceAvailable));
        assertEquals(expectedException.getMessage(), actualException.getMessage());
        verify(hashGenerator).getAvailableHashes(spaceAvailable);
    }

    @Test
    @DisplayName("Should successfully generate new hashes asynchronously")
    void shouldSuccessfullyGenerateNewHashesAsynchronously() {
        CompletableFuture<Void> expectedFuture = CompletableFuture.completedFuture(null);
        when(hashGenerator.generateHashes()).thenReturn(expectedFuture);
        CompletableFuture<Void> result = cacheRefillService.generateNewHashes();
        assertSame(expectedFuture, result);
        verify(hashGenerator).generateHashes();
    }

    @Test
    @DisplayName("Should return completed future when hash generation succeeds")
    void shouldReturnCompletedFutureWhenHashGenerationSucceeds() {
        CompletableFuture<Void> completedFuture = CompletableFuture.completedFuture(null);
        when(hashGenerator.generateHashes()).thenReturn(completedFuture);
        CompletableFuture<Void> result = cacheRefillService.generateNewHashes();
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
        verify(hashGenerator).generateHashes();
    }

    @Test
    @DisplayName("Should return failed future when hash generation fails")
    void shouldReturnFailedFutureWhenHashGenerationFails() {
        RuntimeException exception = new RuntimeException("Generation failed");
        CompletableFuture<Void> failedFuture = CompletableFuture.failedFuture(exception);
        when(hashGenerator.generateHashes()).thenReturn(failedFuture);
        CompletableFuture<Void> result = cacheRefillService.generateNewHashes();
        assertTrue(result.isDone());
        assertTrue(result.isCompletedExceptionally());
        verify(hashGenerator).generateHashes();
    }

    @Test
    @DisplayName("Should handle null return from hash generator during async generation")
    void shouldHandleNullReturnFromHashGeneratorDuringAsyncGeneration() {
        when(hashGenerator.generateHashes()).thenReturn(null);
        CompletableFuture<Void> result = cacheRefillService.generateNewHashes();
        assertNull(result);
        verify(hashGenerator).generateHashes();
    }
}