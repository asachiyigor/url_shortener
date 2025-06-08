package faang.school.urlshortenerservice.service;

import faang.school.urlshortenerservice.entity.Hash;
import faang.school.urlshortenerservice.repository.HashRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class HashGeneratorService {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int BASE = ALPHABET.length();
    private final HashRepository hashRepository;

    @Transactional
    public void generateHashesInternal(List<Long> sequences) {
        if (sequences == null || sequences.isEmpty()) {
            log.warn("Не были предоставлены значения последовательностей для генерации хешей!");
            return;
        }
        log.info("Примеры значений последовательности: первое={}, последнее={}",
                sequences.get(0), sequences.get(sequences.size() - 1));
        List<Hash> hashes = sequences.stream()
                .map(seq -> {
                    String hash = encodeToBase62(seq);
                    log.debug("Сгенерирован хеш {} для последовательности {}", hash, seq);
                    return new Hash(hash);
                })
                .collect(Collectors.toList());
        log.info("Сгенерировано {} хешей, сохраняем в базу данных", hashes.size());
        var savedHashes = hashRepository.saveAll(hashes);
        log.info("Успешно сохранено {} хешей в базе данных", savedHashes.size());
    }

    private String encodeToBase62(long value) {
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }

        StringBuilder sb = new StringBuilder();
        long remaining = value;
        while (remaining > 0) {
            int remainder = (int) (remaining % BASE);
            sb.append(ALPHABET.charAt(remainder));
            remaining /= BASE;
        }
        return sb.reverse().toString();
    }
}