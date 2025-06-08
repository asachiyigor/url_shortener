package faang.school.urlshortenerservice.generator;

import faang.school.urlshortenerservice.service.CacheRefillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalHashCache {
    private final HashGenerator hashGenerator;
    private final CacheRefillService cacheRefillService;
    private final BlockingQueue<String> hashCache;
    private final AtomicBoolean isRefilling;

    @Value("${url.shortener.cache.capacity}")
    private int cacheCapacity;

    @Value("${url.shortener.cache.refill-threshold}")
    private int refillThresholdPercent;

    @PostConstruct
    public void init() {
        log.info("Инициализация LocalHashCache с емкостью: {}", cacheCapacity);
        long availableHashesCount = hashGenerator.getAvailableHashesCount();
        log.info("Найдено {} доступных хешей (FREE или RESERVED) в базе данных", availableHashesCount);
        if (availableHashesCount < cacheCapacity) {
            log.info("Необходимо сгенерировать больше хешей. Требуется: {}, Доступно: {}",
                    cacheCapacity, availableHashesCount);
            try {
                log.info("Генерация новых хешей...");
                cacheRefillService.generateNewHashes().get();
                log.info("Начальная генерация хешей завершена");
            } catch (Exception e) {
                log.error("Ошибка при генерации начальных хешей", e);
            }
        } else {
            log.info("Достаточное количество хешей уже доступно в базе данных");
        }
        refillCache();
        log.info("Кэш инициализирован с {} хешами", hashCache.size());
    }

    @Transactional
    public String getNextHash() {
        log.debug("Перед извлечением: Размер кэша = {}", hashCache.size());
        String hashValue = hashCache.poll();
        log.debug("После извлечения: Размер кэша = {}, Полученный хеш = {}", hashCache.size(), hashValue);
        log.info("Получен хеш из кэша. Размер кэша: {}", hashCache.size());
        if (hashValue != null && isNeedRefill()) {
            log.info("Размер кэша ниже порогового значения ({}%). Запуск пополнения...", refillThresholdPercent);
            triggerAsyncRefill();
        }
        return hashValue;
    }

    private boolean isNeedRefill() {
        int currentSize = hashCache.size();
        int onePercent = cacheCapacity / 100;
        if (onePercent == 0) return true;
        int currentPercent = (currentSize * 100) / cacheCapacity;
        log.info("Текущий процент заполнения кэша: {}%", currentPercent);
        return currentPercent < refillThresholdPercent;
    }

    private void triggerAsyncRefill() {
        if (isRefilling.compareAndSet(false, true)) {
            log.info("Запуск асинхронного пополнения кэша");
            cacheRefillService.generateNewHashes()
                    .thenAccept(v -> {
                        int spaceAvailable = cacheCapacity - hashCache.size();
                        List<String> newHashes = cacheRefillService.refillCache(spaceAvailable);
                        addHashesToCache(newHashes);
                        isRefilling.set(false);
                        log.info("Асинхронное пополнение кэша завершено. Новый размер кэша: {}", hashCache.size());
                    })
                    .exceptionally(e -> {
                        log.error("Ошибка во время асинхронного пополнения кэша", e);
                        isRefilling.set(false);
                        return null;
                    });
        } else {
            log.info("Пополнение уже выполняется, пропускаем");
        }
    }

    private void refillCache() {
        int spaceAvailable = cacheCapacity - hashCache.size();
        int fetchLimit = spaceAvailable > 0 ? spaceAvailable : cacheCapacity;
        List<String> newHashes = cacheRefillService.refillCache(fetchLimit);
        log.info("Получено {} хешей для пополнения кэша", newHashes.size());

        if (!newHashes.isEmpty()) {
            if (spaceAvailable <= 0 && !hashCache.isEmpty()) {
                log.info("Очистка существующего кэша для добавления новых хешей");
                hashCache.clear();
            }
            addHashesToCache(newHashes);
            log.info("Кэш пополнен. Новый размер: {}", hashCache.size());
        } else {
            log.info("Нет доступных хешей для пополнения");
        }
    }

    private void addHashesToCache(List<String> hashes) {
        hashes.forEach(hash -> {
            if (!hashCache.offer(hash)) {
                log.warn("Не удалось добавить хеш {} в кэш - очередь заполнена", hash);
            }
        });
        log.info("Добавлено {} хешей в кэш. Новый размер: {}", hashes.size(), hashCache.size());
    }
}