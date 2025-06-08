package faang.school.urlshortenerservice.generator;

import faang.school.urlshortenerservice.entity.HashStatus;
import faang.school.urlshortenerservice.repository.HashRepository;
import faang.school.urlshortenerservice.service.HashGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class HashGenerator {
    private final HashRepository hashRepository;
    private final HashGeneratorService hashGeneratorService;

    @Value("${url.shortener.cache.capacity}")
    private int capacity;

    @Value("${url.shortener.hash.batch-size}")
    private int batchSize;

    @Async
    public CompletableFuture<Void> generateHashes() {
        try {
            long existingHashesCount = hashRepository.countExistingHashes();

            if (existingHashesCount < capacity) {
                long hashesToGenerate = capacity - existingHashesCount;
                int hashesToGenerateInt = (int) Math.min(hashesToGenerate, Integer.MAX_VALUE);
                log.info("Первичная генерация хешей: существует {}, требуется {}, будет сгенерировано {}",
                        existingHashesCount, capacity, hashesToGenerateInt);
                List<Long> initialSequences = hashRepository.getNextSequenceValues(hashesToGenerateInt);
                log.info("Получено {} значений последовательности для первичной генерации", initialSequences.size());
                hashGeneratorService.generateHashesInternal(initialSequences);
                log.info("Первичная генерация хешей завершена успешно");
            } else {
                log.info("Начинаем генерацию хешей с размером пакета: {}", batchSize);
                int batchSizeInt = (int) Math.min(batchSize, Integer.MAX_VALUE);
                List<Long> sequences = hashRepository.getNextSequenceValues(batchSizeInt);
                log.info("Получено {} значений последовательности из базы данных", sequences.size());
                hashGeneratorService.generateHashesInternal(sequences);
            }
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            log.error("Ошибка при генерации хешей", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    @Transactional
    public List<String> getAvailableHashes(int limit) {
        log.info("Запрашиваем {} доступных хешей", limit);
        List<String> hashes = hashRepository.findAvailableHashes(limit);

        if (!hashes.isEmpty()) {
            int updatedCount = hashRepository.markAsReserved(hashes);
            log.info("Помечено {} хешей как ЗАРЕЗЕРВИРОВАННЫЕ", updatedCount);
            if (updatedCount < hashes.size()) {
                log.warn("Не все хеши были помечены как ЗАРЕЗЕРВИРОВАННЫЕ: запрошено {}, помечено {}",
                        hashes.size(), updatedCount);
            }
        }

        log.info("Получено {} хешей из базы данных", hashes.size());
        return hashes;
    }

    public long getAvailableHashesCount() {
        return hashRepository.countByStatusIn(Arrays.asList(HashStatus.FREE, HashStatus.RESERVED));
    }
}