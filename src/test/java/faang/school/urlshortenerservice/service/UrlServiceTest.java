package faang.school.urlshortenerservice.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import faang.school.urlshortenerservice.entity.Hash;
import faang.school.urlshortenerservice.entity.Url;
import faang.school.urlshortenerservice.exception.InvalidHashException;
import faang.school.urlshortenerservice.exception.InvalidUrlException;
import faang.school.urlshortenerservice.exception.UrlNotFoundException;
import faang.school.urlshortenerservice.generator.LocalHashCache;
import faang.school.urlshortenerservice.repository.HashRepository;
import faang.school.urlshortenerservice.repository.UrlCacheRepository;
import faang.school.urlshortenerservice.repository.UrlRepository;
import faang.school.urlshortenerservice.validation.UrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("URL Service Tests")
class UrlServiceTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private HashRepository hashRepository;

    @Mock
    private UrlCacheRepository urlCacheRepository;

    @Mock
    private LocalHashCache hashCache;

    @Mock
    private UrlValidator urlValidator;

    @Mock
    private UrlVisitService urlVisitService;

    @Mock
    private LoadingCache<String, String> localCache;

    @InjectMocks
    private UrlService urlService;

    private static final String VALID_URL = "https://example.com";
    private static final String INVALID_URL = "invalid-url";
    private static final String VALID_HASH = "abc123";
    private static final String INVALID_HASH = "invalid@hash";
    private static final String BASE_URL = "localhost:8080";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(urlService, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(urlService, "localCache", localCache);
    }

    @Nested
    @DisplayName("Create Short URL Tests")
    class CreateShortUrlTests {

        @Test
        @DisplayName("Should create short URL successfully when all conditions are met")
        void shouldCreateShortUrlSuccessfully() {
            Hash mockHash = new Hash();
            mockHash.setValue(VALID_HASH);
            when(urlValidator.isValid(VALID_URL)).thenReturn(true);
            when(hashCache.getNextHash()).thenReturn(VALID_HASH);
            when(hashRepository.markAsUsed(VALID_HASH)).thenReturn(1);
            when(hashRepository.findByValue(VALID_HASH)).thenReturn(Optional.of(mockHash));
            String result = urlService.createShortUrl(VALID_URL);
            assertEquals(BASE_URL + "/" + VALID_HASH, result);
            verify(urlRepository).save(any(Url.class));
            verify(urlCacheRepository).saveUrl(VALID_HASH, VALID_URL);
            verify(localCache).put(VALID_HASH, VALID_URL);
        }

        @Test
        @DisplayName("Should throw InvalidUrlException when URL is invalid")
        void shouldThrowInvalidUrlExceptionWhenUrlIsInvalid() {
            when(urlValidator.isValid(INVALID_URL)).thenReturn(false);
            assertThrows(InvalidUrlException.class, () -> urlService.createShortUrl(INVALID_URL));
            verify(hashCache, never()).getNextHash();
        }

        @Test
        @DisplayName("Should throw RuntimeException when hash generation fails")
        void shouldThrowRuntimeExceptionWhenHashGenerationFails() {
            when(urlValidator.isValid(VALID_URL)).thenReturn(true);
            when(hashCache.getNextHash()).thenReturn(null);
            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> urlService.createShortUrl(VALID_URL));
            assertTrue(exception.getMessage().contains("закончились доступные хеши"));
        }

        @Test
        @DisplayName("Should throw RuntimeException when hash is not found in database")
        void shouldThrowRuntimeExceptionWhenHashNotFoundInDatabase() {
            // Given
            when(urlValidator.isValid(VALID_URL)).thenReturn(true);
            when(urlRepository.findByOriginalUrl(VALID_URL)).thenReturn(Optional.empty());
            when(hashCache.getNextHash()).thenReturn(VALID_HASH);
            when(hashRepository.findByValue(VALID_HASH)).thenReturn(Optional.empty()); // Хеш не найден в БД
            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> urlService.createShortUrl(VALID_URL));
            assertTrue(exception.getMessage().contains("Ошибка генерации хеша"));
            verify(urlValidator).isValid(VALID_URL);
            verify(urlRepository).findByOriginalUrl(VALID_URL);
            verify(hashCache).getNextHash();
            verify(hashRepository).findByValue(VALID_HASH);
            verify(hashRepository, never()).markAsUsed(any());
            verify(urlRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should return existing short URL when original URL already exists")
        void shouldReturnExistingShortUrlWhenOriginalUrlExists() {
            Hash existingHash = new Hash();
            existingHash.setValue("existing");
            Url existingUrl = new Url(existingHash, VALID_URL);
            when(urlValidator.isValid(VALID_URL)).thenReturn(true);
            when(urlRepository.findByOriginalUrl(VALID_URL)).thenReturn(Optional.of(existingUrl));
            String result = urlService.createShortUrl(VALID_URL);
            assertTrue(result.contains("existing"));
            verify(hashCache, never()).getNextHash(); // НЕ должно вызываться
            verify(urlRepository, never()).save(any()); // НЕ должно вызываться
        }

        @Test
        @DisplayName("Should handle race condition and return existing URL")
        void shouldHandleRaceConditionAndReturnExistingUrl() {
            Hash hash = new Hash();
            hash.setValue(VALID_HASH);
            Hash existingHash = new Hash();
            existingHash.setValue("race123");
            Url existingUrl = new Url(existingHash, VALID_URL);
            when(urlValidator.isValid(VALID_URL)).thenReturn(true);
            when(urlRepository.findByOriginalUrl(VALID_URL))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existingUrl));
            when(hashCache.getNextHash()).thenReturn(VALID_HASH);
            when(hashRepository.findByValue(VALID_HASH)).thenReturn(Optional.of(hash));
            when(urlRepository.save(any(Url.class))).thenThrow(new DataIntegrityViolationException("Duplicate"));
            String result = urlService.createShortUrl(VALID_URL);
            assertTrue(result.contains("race123"));
            verify(urlRepository, times(2)).findByOriginalUrl(VALID_URL);
            verify(hashRepository, never()).markAsUsed(any());
        }

        @Test
        @DisplayName("Should successfully create new short URL")
        void shouldSuccessfullyCreateNewShortUrl() {
            Hash hash = new Hash();
            hash.setValue(VALID_HASH);
            when(urlValidator.isValid(VALID_URL)).thenReturn(true);
            when(urlRepository.findByOriginalUrl(VALID_URL)).thenReturn(Optional.empty());
            when(hashCache.getNextHash()).thenReturn(VALID_HASH);
            when(hashRepository.findByValue(VALID_HASH)).thenReturn(Optional.of(hash));
            when(hashRepository.markAsUsed(VALID_HASH)).thenReturn(1);
            String result = urlService.createShortUrl(VALID_URL);
            assertTrue(result.contains(VALID_HASH));
            verify(urlValidator).isValid(VALID_URL);
            verify(urlRepository).findByOriginalUrl(VALID_URL);
            verify(hashCache).getNextHash();
            verify(hashRepository).findByValue(VALID_HASH);
            verify(urlRepository).save(any(Url.class));
            verify(hashRepository).markAsUsed(VALID_HASH);
            verify(urlCacheRepository).saveUrl(VALID_HASH, VALID_URL);
            assertEquals("localhost:8080/" + VALID_HASH, result);
        }

        @Test
        @DisplayName("Should throw RuntimeException when no hash available")
        void shouldThrowRuntimeExceptionWhenNoHashAvailable() {
            when(urlValidator.isValid(VALID_URL)).thenReturn(true);
            when(urlRepository.findByOriginalUrl(VALID_URL)).thenReturn(Optional.empty());
            when(hashCache.getNextHash()).thenReturn(null); // Нет доступных хешей
            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> urlService.createShortUrl(VALID_URL));
            assertTrue(exception.getMessage().contains("закончились доступные хеши"));
            verify(hashRepository, never()).findByValue(any());
            verify(urlRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Redirect Tests")
    class RedirectTests {

        @Test
        @DisplayName("Should redirect successfully when hash is valid and URL exists")
        void shouldRedirectSuccessfullyWhenHashIsValidAndUrlExists() {
            when(localCache.get(VALID_HASH)).thenReturn(VALID_URL);
            ResponseEntity<Void> result = urlService.redirect(VALID_HASH);
            assertEquals(HttpStatus.FOUND, result.getStatusCode());
            assertEquals(URI.create(VALID_URL), result.getHeaders().getLocation());
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            verify(urlVisitService, timeout(1000)).registerVisit(VALID_HASH);
        }

        @Test
        @DisplayName("Should return not found when hash is invalid")
        void shouldReturnNotFoundWhenHashIsInvalid() {
            assertThrows(InvalidHashException.class, () -> urlService.redirect(INVALID_HASH));
        }

        @Test
        @DisplayName("Should return not found when URL is not found")
        void shouldReturnNotFoundWhenUrlIsNotFound() {
            UrlNotFoundException urlNotFound = new UrlNotFoundException(VALID_HASH);
            RuntimeException runtimeException = new RuntimeException(urlNotFound);
            when(localCache.get(VALID_HASH)).thenThrow(runtimeException);
            ResponseEntity<Void> result = urlService.redirect(VALID_HASH);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        }

        @Test
        @DisplayName("Should return internal server error when unexpected exception occurs")
        void shouldReturnInternalServerErrorWhenUnexpectedExceptionOccurs() {
            IllegalStateException cause = new IllegalStateException("Database connection lost");
            when(localCache.get(VALID_HASH)).thenThrow(cause);
            ResponseEntity<Void> result = urlService.redirect(VALID_HASH);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        }
    }

    @Nested
    @DisplayName("Get Original URL Tests")
    class GetOriginalUrlTests {

        @Test
        @DisplayName("Should return original URL from cache successfully")
        void shouldReturnOriginalUrlFromCacheSuccessfully() {
            when(localCache.get(VALID_HASH)).thenReturn(VALID_URL);
            String result = urlService.getOriginalUrl(VALID_HASH);
            assertEquals(VALID_URL, result);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            verify(urlVisitService, timeout(1000)).registerVisit(VALID_HASH);
        }

        @Test
        @DisplayName("Should throw UrlNotFoundException when UncheckedExecutionException contains UrlNotFoundException")
        void shouldThrowUrlNotFoundExceptionWhenUncheckedExecutionExceptionContainsUrlNotFoundException() {
            UrlNotFoundException urlNotFound = new UrlNotFoundException(VALID_HASH);
            UncheckedExecutionException uncheckedExecutionException = new UncheckedExecutionException(urlNotFound);
            when(localCache.get(VALID_HASH)).thenThrow(uncheckedExecutionException);
            UrlNotFoundException thrown = assertThrows(
                    UrlNotFoundException.class,
                    () -> urlService.getOriginalUrl(VALID_HASH)
            );
            assertEquals(urlNotFound, thrown);
            verify(localCache).get(VALID_HASH);
        }

        @Test
        @DisplayName("Should throw RuntimeException when UncheckedExecutionException contains other exception")
        void shouldThrowRuntimeExceptionWhenUncheckedExecutionExceptionContainsOtherException() {
            IllegalStateException cause = new IllegalStateException("Cache error");
            UncheckedExecutionException uncheckedExecutionException = new UncheckedExecutionException(cause);
            when(localCache.get(VALID_HASH)).thenThrow(uncheckedExecutionException);
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> urlService.getOriginalUrl(VALID_HASH)
            );
            assertEquals("Ошибка при получении URL", thrown.getMessage());
            assertEquals(cause, thrown.getCause());
            verify(localCache).get(VALID_HASH);
        }

        @Test
        @DisplayName("Should throw RuntimeException when unexpected exception occurs")
        void shouldThrowRuntimeExceptionWhenUnexpectedExceptionOccurs() {
            NullPointerException unexpectedException = new NullPointerException("Unexpected error");
            when(localCache.get(VALID_HASH)).thenThrow(unexpectedException);
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> urlService.getOriginalUrl(VALID_HASH)
            );
            assertEquals("Ошибка при получении URL", thrown.getMessage());
            assertEquals(unexpectedException, thrown.getCause());
            verify(localCache).get(VALID_HASH);
        }

        @Test
        @DisplayName("Should return original URL when successful")
        void shouldReturnOriginalUrlWhenSuccessful() {
            String expectedUrl = "https://example.com";
            when(localCache.get(VALID_HASH)).thenReturn(expectedUrl);
            String result = urlService.getOriginalUrl(VALID_HASH);
            assertEquals(expectedUrl, result);
            verify(localCache).get(VALID_HASH);
        }
    }

    @Nested
    @DisplayName("Load URL Tests")
    class LoadUrlTests {

        @Test
        @DisplayName("Should load URL from Redis cache when available")
        void shouldLoadUrlFromRedisCacheWhenAvailable() {
            when(urlCacheRepository.getUrl(VALID_HASH)).thenReturn(Optional.of(VALID_URL));
            String result = invokeLoadUrl(VALID_HASH);
            assertEquals(VALID_URL, result);
            verify(urlCacheRepository).getUrl(VALID_HASH);
            verify(urlRepository, never()).findByHashValue(anyString());
        }

        @Test
        @DisplayName("Should load URL from database when not in Redis cache")
        void shouldLoadUrlFromDatabaseWhenNotInRedisCache() {
            Url mockUrl = new Url();
            mockUrl.setOriginalUrl(VALID_URL);
            when(urlCacheRepository.getUrl(VALID_HASH)).thenReturn(Optional.empty());
            when(urlRepository.findByHashValue(VALID_HASH)).thenReturn(Optional.of(mockUrl));
            String result = invokeLoadUrl(VALID_HASH);
            assertEquals(VALID_URL, result);
            verify(urlCacheRepository).getUrl(VALID_HASH);
            verify(urlRepository).findByHashValue(VALID_HASH);
            verify(urlCacheRepository).saveUrl(VALID_HASH, VALID_URL);
        }

        @Test
        @DisplayName("Should throw UrlNotFoundException when URL not found in database")
        void shouldThrowUrlNotFoundExceptionWhenUrlNotFoundInDatabase() {
            when(urlCacheRepository.getUrl(VALID_HASH)).thenReturn(Optional.empty());
            when(urlRepository.findByHashValue(VALID_HASH)).thenReturn(Optional.empty());
            UrlNotFoundException thrown = assertThrows(
                    UrlNotFoundException.class,
                    () -> invokeLoadUrl(VALID_HASH)
            );
            assertNotNull(thrown);
            verify(urlCacheRepository).getUrl(VALID_HASH);
            verify(urlRepository).findByHashValue(VALID_HASH);
            verify(urlCacheRepository, never()).saveUrl(anyString(), anyString());
        }

        @Test
        @DisplayName("Should handle Redis exception gracefully and load from database")
        void shouldHandleRedisExceptionGracefullyAndLoadFromDatabase() {
            Url mockUrl = new Url();
            mockUrl.setOriginalUrl(VALID_URL);
            when(urlCacheRepository.getUrl(VALID_HASH)).thenThrow(new RuntimeException("Redis error"));
            when(urlRepository.findByHashValue(VALID_HASH)).thenReturn(Optional.of(mockUrl));
            String result = invokeLoadUrl(VALID_HASH);
            assertEquals(VALID_URL, result);
            verify(urlCacheRepository).getUrl(VALID_HASH);
            verify(urlRepository).findByHashValue(VALID_HASH);
            verify(urlCacheRepository).saveUrl(VALID_HASH, VALID_URL);
        }

        @Test
        @DisplayName("Should handle database exception after Redis miss")
        void shouldHandleDatabaseExceptionAfterRedisMiss() {
            when(urlCacheRepository.getUrl(VALID_HASH)).thenReturn(Optional.empty());
            when(urlRepository.findByHashValue(VALID_HASH)).thenThrow(new RuntimeException("Database error"));
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> invokeLoadUrl(VALID_HASH)
            );
            assertTrue(thrown.getMessage().contains("Unexpected exception") ||
                    thrown.getMessage().contains("Database error"));
            verify(urlCacheRepository).getUrl(VALID_HASH);
            verify(urlRepository).findByHashValue(VALID_HASH);
        }

        @Test
        @DisplayName("Should handle cache save failure gracefully")
        void shouldHandleCacheSaveFailureGracefully() {
            Url mockUrl = new Url();
            mockUrl.setOriginalUrl(VALID_URL);
            when(urlCacheRepository.getUrl(VALID_HASH)).thenReturn(Optional.empty());
            when(urlRepository.findByHashValue(VALID_HASH)).thenReturn(Optional.of(mockUrl));
            doThrow(new RuntimeException("Cache save error")).when(urlCacheRepository).saveUrl(VALID_HASH, VALID_URL);
            String result = invokeLoadUrl(VALID_HASH);
            assertEquals(VALID_URL, result); // Должен вернуть URL несмотря на ошибку сохранения в кеш
            verify(urlCacheRepository).getUrl(VALID_HASH);
            verify(urlRepository).findByHashValue(VALID_HASH);
            verify(urlCacheRepository).saveUrl(VALID_HASH, VALID_URL);
        }

        private String invokeLoadUrl(String hashValue) {
            try {
                return ReflectionTestUtils.invokeMethod(urlService, "loadUrl", hashValue);
            } catch (Exception e) {
                Throwable current = e;
                while (current != null) {
                    if (current instanceof UrlNotFoundException) {
                        throw (UrlNotFoundException) current;
                    }
                    current = current.getCause();
                }
                throw new RuntimeException("Unexpected exception", e);
            }
        }
    }

    @Nested
    @DisplayName("Hash Validation Tests")
    class HashValidationTests {

        @Test
        @DisplayName("Should validate correct hash format")
        void shouldValidateCorrectHashFormat() {
            assertDoesNotThrow(() -> invokeValidateHash("abc123"));
            assertDoesNotThrow(() -> invokeValidateHash("ABC123"));
            assertDoesNotThrow(() -> invokeValidateHash("abcDEF"));
            assertDoesNotThrow(() -> invokeValidateHash("123456"));
            assertDoesNotThrow(() -> invokeValidateHash("a1b2c3d4")); // 8 chars
        }

        @Test
        @DisplayName("Should throw InvalidHashException for invalid hash formats")
        void shouldThrowInvalidHashExceptionForInvalidHashFormats() {
            assertThrows(InvalidHashException.class, () -> invokeValidateHash("abc12"));
            assertThrows(InvalidHashException.class, () -> invokeValidateHash("abc123def"));
            assertThrows(InvalidHashException.class, () -> invokeValidateHash("abc-123"));
            assertThrows(InvalidHashException.class, () -> invokeValidateHash("abc@123"));
            assertThrows(InvalidHashException.class, () -> invokeValidateHash("abc 123"));
            assertThrows(InvalidHashException.class, () -> invokeValidateHash(null));
            assertThrows(InvalidHashException.class, () -> invokeValidateHash(""));
        }
        private void invokeValidateHash(String hash) {
            ReflectionTestUtils.invokeMethod(urlService, "validateHash", hash);
        }
    }

    @Nested
    @DisplayName("URL Validation Tests")
    class UrlValidationTests {

        @Test
        @DisplayName("Should validate URL using UrlValidator")
        void shouldValidateUrlUsingUrlValidator() {
            when(urlValidator.isValid(VALID_URL)).thenReturn(true);
            assertDoesNotThrow(() -> invokeValidateUrl(VALID_URL));
            verify(urlValidator).isValid(VALID_URL);
        }

        @Test
        @DisplayName("Should throw InvalidUrlException when UrlValidator returns false")
        void shouldThrowInvalidUrlExceptionWhenUrlValidatorReturnsFalse() {
            when(urlValidator.isValid(INVALID_URL)).thenReturn(false);
            assertThrows(InvalidUrlException.class, () -> invokeValidateUrl(INVALID_URL));
        }

        private void invokeValidateUrl(String url) {
            ReflectionTestUtils.invokeMethod(urlService, "validateUrl", url);
        }
    }

    @Nested
    @DisplayName("Utility Method Tests")
    class UtilityMethodTests {

        @Test
        @DisplayName("Should construct short URL correctly")
        void shouldConstructShortUrlCorrectly() {
            String result = ReflectionTestUtils.invokeMethod(urlService, "constructShortUrl", VALID_HASH);
            assertEquals(BASE_URL + "/" + VALID_HASH, result);
        }

        @Test
        @DisplayName("Should register visit asynchronously without blocking")
        void shouldRegisterVisitAsynchronouslyWithoutBlocking() {
            assertDoesNotThrow(() ->
                    ReflectionTestUtils.invokeMethod(urlService, "registerVisitAsync", VALID_HASH));
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            verify(urlVisitService, timeout(1000)).registerVisit(VALID_HASH);
        }

        @Test
        @DisplayName("Should handle async visit registration errors gracefully")
        void shouldHandleAsyncVisitRegistrationErrorsGracefully() {
            assertDoesNotThrow(() ->
                    ReflectionTestUtils.invokeMethod(urlService, "registerVisitAsync", VALID_HASH));
        }
    }
}