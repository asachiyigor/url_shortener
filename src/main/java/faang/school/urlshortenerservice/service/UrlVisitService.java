package faang.school.urlshortenerservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlVisitService {
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, AtomicInteger> visitsBuffer = new ConcurrentHashMap<>();

    public void registerVisit(String hashValue) {
        if (hashValue == null || hashValue.trim().isEmpty()) {
            log.warn("Attempted to register visit with invalid hash value: [{}]. Request ignored.", hashValue);
            return;
        }
        visitsBuffer.computeIfAbsent(hashValue, h -> new AtomicInteger()).incrementAndGet();
    }

    @Scheduled(fixedDelayString = "${visits.batch.interval:30000}")
    @Transactional
    public void flushVisitsBatch() {
        if (visitsBuffer.isEmpty()) {
            return;
        }

        List<Object[]> batchParams = new ArrayList<>();
        visitsBuffer.forEach((hash, counter) -> {
            int count = counter.getAndSet(0);
            if (count > 0) {
                batchParams.add(new Object[]{count, hash});
            }
        });

        if (!batchParams.isEmpty()) {
            String sql = "UPDATE url SET visits_count = visits_count + ? WHERE hash_value = ?";

            int[] results = jdbcTemplate.batchUpdate(sql, batchParams);
            int totalUpdated = Arrays.stream(results).sum();
            int totalVisits = batchParams.stream().mapToInt(params -> (Integer) params[0]).sum();

            log.info("Пакетное обновление выполнено: обновлено {} URL с общим числом {} посещений",
                    totalUpdated, totalVisits);
        }
    }
}