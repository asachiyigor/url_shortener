package faang.school.urlshortenerservice.service;

import com.github.benmanes.caffeine.cache.Caffeine;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.sql.exec.ExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlService {
    private final UrlRepository urlRepository;
    private final HashRepository hashRepository;
    private final UrlCacheRepository urlCacheRepository;
    private final LocalHashCache hashCache;
    private final UrlValidator urlValidator;
    private final UrlVisitService urlVisitService;
    private final LoadingCache<String, String> localCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .recordStats()
            .build(this::loadUrl);


    @Value("${server.host:localhost}:${server.port:8080}")
    private String baseUrl;

    @Transactional
    public String createShortUrl(String originalUrl) {
        log.debug("Начинаем процесс создания короткого URL для: {}", originalUrl);
        validateUrl(originalUrl);

        Optional<Url> existingUrl = urlRepository.findByOriginalUrl(originalUrl);
        if (existingUrl.isPresent()) {
            String existingShortUrl = constructShortUrl(existingUrl.get().getHash().getValue());
            log.debug("Найден существующий короткий URL: {} -> {}", existingShortUrl, originalUrl);
            return existingShortUrl;
        }

        String hashValue = hashCache.getNextHash();
        if (hashValue == null) {
            log.error("Не удалось получить хеш из кеша");
            throw new RuntimeException("Невозможно создать короткий URL: закончились доступные хеши");
        }

        Optional<Hash> hashOptional = hashRepository.findByValue(hashValue);
        if (hashOptional.isEmpty()) {
            log.error("Хеш не найден в базе данных: {}", hashValue);
            throw new RuntimeException("Ошибка генерации хеша");
        }

        Hash hash = hashOptional.get();
        log.debug("Получен хеш для использования: {}", hashValue);

        try {
            Url url = new Url(hash, originalUrl);
            urlRepository.save(url);
            log.debug("Сохранен URL в базе данных: {} -> {}", hashValue, originalUrl);

            int updated = hashRepository.markAsUsed(hashValue);
            if (updated == 0) {
                log.warn("Хеш {} не был помечен как USED, возможно уже используется", hashValue);
            } else {
                log.debug("Хеш помечен как USED: {}", hashValue);
            }

            urlCacheRepository.saveUrl(hashValue, originalUrl);
            log.debug("Кеширован URL в Redis: {} -> {}", hashValue, originalUrl);

            localCache.put(hashValue, originalUrl);
            log.debug("Кеширован URL в Caffeine: {} -> {}", hashValue, originalUrl);

            String shortUrl = constructShortUrl(hashValue);
            log.debug("Успешно создан короткий URL: {} -> {}", shortUrl, originalUrl);
            return shortUrl;

        } catch (DataIntegrityViolationException e) {
            log.debug("Обнаружен race condition для URL: {}, ищем существующий", originalUrl);

            Optional<Url> raceConditionUrl = urlRepository.findByOriginalUrl(originalUrl);
            if (raceConditionUrl.isPresent()) {
                String shortUrl = constructShortUrl(raceConditionUrl.get().getHash().getValue());
                log.debug("Найден URL после race condition: {} -> {}", shortUrl, originalUrl);
                return shortUrl;
            }

            log.error("Критическая ошибка: не удалось найти URL после race condition для: {}", originalUrl);
            throw new RuntimeException("Ошибка при создании короткого URL", e);
        }
    }

    public ResponseEntity<Void> redirect(String hashValue) {
        log.debug("Обрабатываем запрос на перенаправление для хеша: {}", hashValue);
        validateHash(hashValue);

        try {
            String originalUrl = getOriginalUrl(hashValue);
            log.debug("Перенаправляем {} на {}", hashValue, originalUrl);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(originalUrl))
                    .build();
        } catch (UrlNotFoundException e) {
            log.warn("URL не найден для хеша: {}", hashValue);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Ошибка при перенаправлении для хеша: {}", hashValue, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    public String getOriginalUrl(String hashValue) {
        log.debug("Получаем оригинальный URL для хеша: {}", hashValue);
        try {
            String originalUrl = localCache.get(hashValue);
            registerVisitAsync(hashValue);
            return originalUrl;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error("Ошибка при получении URL для хеша: {}", hashValue, e);

            if (cause instanceof UrlNotFoundException) {
                throw (UrlNotFoundException) cause;
            }
            throw new RuntimeException("Ошибка при получении URL", cause);
        } catch (UncheckedExecutionException e) {
            Throwable cause = e.getCause();
            log.error("Ошибка при получении URL для хеша: {}", hashValue, e);

            if (cause instanceof UrlNotFoundException) {
                throw (UrlNotFoundException) cause;
            }
            throw new RuntimeException("Ошибка при получении URL", cause);
        } catch (Exception e) {
            log.error("Неожиданная ошибка при получении URL для хеша: {}", hashValue, e);
            throw new RuntimeException("Ошибка при получении URL", e);
        }
    }

    private String loadUrl(String hashValue) {
        log.debug("Загрузка URL для хеша {} в локальный кэш", hashValue);
        try {
            Optional<String> redisCachedUrl = urlCacheRepository.getUrl(hashValue);
            if (redisCachedUrl.isPresent()) {
                String url = redisCachedUrl.get();
                log.debug("Загружено из L2 кэша (Redis): {} -> {}", hashValue, url);
                return url;
            }
        } catch (Exception e) {
            log.warn("Ошибка при обращении к Redis для хеша {}: {}", hashValue, e.getMessage());
        }
        log.debug("В L2 кэше не найдено, загружаем из базы данных (L3)");
        Url url = urlRepository.findByHashValue(hashValue)
                .orElseThrow(() -> {
                    log.error("URL не найден в базе данных для хеша: {}", hashValue);
                    return new UrlNotFoundException(hashValue);
                });
        String originalUrl = url.getOriginalUrl();
        try {
            urlCacheRepository.saveUrl(hashValue, originalUrl);
            log.debug("Обновлен L2 кэш (Redis) результатом из базы данных: {} -> {}", hashValue, originalUrl);
        } catch (Exception e) {
            log.warn("Не удалось обновить L2 кэш для хеша {}: {}", hashValue, e.getMessage());
        }
        return originalUrl;
    }

    private String constructShortUrl(String hashValue) {
        return baseUrl + "/" + hashValue;
    }

    private void registerVisitAsync(String hashValue) {
        CompletableFuture.runAsync(() -> {
            try {
                urlVisitService.registerVisit(hashValue);
                log.debug("Асинхронно зарегистрировано посещение для хеша: {}", hashValue);
            } catch (Exception e) {
                log.error("Не удалось зарегистрировать посещение для хеша: {}", hashValue, e);
            }
        });
    }

    private void validateUrl(String url) {
        if (!urlValidator.isValid(url)) {
            log.error("Ошибка валидации URL: {}", url);
            throw new InvalidUrlException("Неверный формат URL: " + url);
        }
        log.debug("URL прошел валидацию: {}", url);
    }

    private void validateHash(String hash) {
        if (!isValidHash(hash)) {
            log.error("Ошибка валидации хеша: {}", hash);
            throw new InvalidHashException("Неверный формат хеша: " + hash);
        }
        log.debug("Хеш прошел валидацию: {}", hash);
    }

    private boolean isValidHash(String hash) {
        return hash != null && hash.matches("^[a-zA-Z0-9]{6,8}$");
    }
}