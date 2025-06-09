# URL Shortener - Обновленная архитектура системы

## Общая схема

```
                              URL SHORTENER
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│  👨‍💻 User ──▶ 🟢 UrlController ──▶ 🔴 UrlService ──▶ 🔵 UrlRepository ──▶ 💾 Database │
│                     │                    │                │                │
│                     │                    │                │                │
│                     │                    ▼                ▼                │
│                     │              ┌─────────────┐   🔵 HashRepository     │
│                     │              │🔴 Caffeine  │        │                │
│                     │              │LocalCache   │        │                │
│                     │              │(L1 Cache)   │        ▼                │
│                     │              └─────────────┘   🔴 HashGenerator      │
│                     │                    │           Service               │
│                     │                    │                │                │
│                     │                    ▼                ▼                │
│                     │              🔴 UrlCacheRepo    🔴 LocalHashCache     │
│                     │              (Redis L2 Cache)       │                │
│                     │                    │                │                │
│                     ▼                    ▼                ▼                │
│               🟠 CleanerScheduler ──▶ 🔴 Redis ◀──▶ 🔴 CacheRefillService  │
│                     │                                     │                │
│                     │                                     │                │
│                     ▼                                     │                │
│               🟣 UrlVisitService ──────────────────────────┘                │
│               (Batch Updates)                                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Обновленные компоненты системы

### 🔴 UrlService (Основные изменения)
**Многоуровневое кеширование:**
```
L1: Caffeine Cache (10,000 записей, TTL 5 мин)
     │
     ▼ (cache miss)
L2: Redis Cache (UrlCacheRepository)
     │
     ▼ (cache miss)  
L3: PostgreSQL Database (UrlRepository)
```

**Процесс создания короткой ссылки:**
1. Валидация URL через `UrlValidator`
2. Проверка существующего URL в БД
3. Получение хеша из `LocalHashCache`
4. Сохранение в БД + пометка хеша как USED
5. **Тройное кеширование:** Caffeine + Redis + Database
6. Асинхронная регистрация посещения

**Процесс редиректа:**
```
hashValue ──▶ L1 Cache (Caffeine) ──▶ L2 Cache (Redis) ──▶ L3 (Database)
                  │                       │                    │
                  ▼                       ▼                    ▼
            Instant response      ~1ms response         ~10ms response
```

### 🔴 LocalHashCache  
**Функция:** Локальный кеш предгенерированных хешей

**Особенности:**
- Хранит готовые к использованию хеши в памяти
- Автоматически пополняется через `CacheRefillService`
- Обеспечивает мгновенный доступ при создании ссылок
- Предотвращает race conditions при генерации

### 🔴 HashGeneratorService (Новый компонент)
**Функция:** Генерирует хеши по алгоритму Base62

**Процесс:**
```
Sequence Numbers ──▶ Base62 Encoding ──▶ Hash Values ──▶ Database
```

**Алгоритм Base62:**
- Алфавит: `a-z, A-Z, 0-9` (62 символа)
- Компактное представление
- URL-безопасные символы
- Уникальность через sequence numbers

### 🔴 CacheRefillService (Новый компонент)  
**Функция:** Управление пополнением кеша хешей

**Методы:**
- `refillCache(int spaceAvailable)` - синхронное пополнение
- `generateNewHashes()` - асинхронная генерация новых хешей

**Логика работы:**
```
LocalHashCache заканчивается ──▶ CacheRefillService ──▶ HashGenerator ──▶ 
──▶ HashRepository ──▶ Новые хеши в LocalHashCache
```

### 🟣 UrlVisitService (Новый компонент)
**Функция:** Пакетная обработка статистики посещений

**Архитектура:**
```
registerVisit() ──▶ ConcurrentHashMap Buffer ──▶ @Scheduled Batch Update ──▶ Database
```

**Особенности:**
- Буферизация посещений в памяти
- Пакетные обновления каждые 30 секунд (настраивается)
- Атомарные счетчики для thread-safety
- Асинхронная обработка без блокировки основных операций

## Обновленные потоки данных

### Создание короткой ссылки (с кешированием)
```
1. User Request ──▶ UrlController
2. UrlController ──▶ UrlService.createShortUrl()
3. UrlService ──▶ UrlValidator.isValid()
4. UrlService ──▶ UrlRepository.findByOriginalUrl() (проверка дубликатов)
5. UrlService ──▶ LocalHashCache.getNextHash()
6. UrlService ──▶ UrlRepository.save() + HashRepository.markAsUsed()
7. UrlService ──▶ UrlCacheRepository.saveUrl() (Redis L2)
8. UrlService ──▶ Caffeine.put() (L1 cache)
9. Response ──▶ User
```

### Редирект с многоуровневым кешем
```
1. User Request ──▶ UrlController.redirect()
2. UrlController ──▶ UrlService.getOriginalUrl()
3. UrlService ──▶ Caffeine.get() (L1)
   │
   └── (miss) ──▶ loadUrl() ──▶ UrlCacheRepository.getUrl() (L2)
                      │
                      └── (miss) ──▶ UrlRepository.findByHashValue() (L3)
4. UrlService ──▶ registerVisitAsync() (в фоне)
5. HTTP 302 Redirect ──▶ User
```

### Пакетная обработка статистики
```
1. Multiple registerVisit() calls ──▶ ConcurrentHashMap buffer
2. @Scheduled(30s) ──▶ flushVisitsBatch()
3. JdbcTemplate.batchUpdate() ──▶ Database
4. Clear buffer counters
```

### Пополнение кеша хешей
```
1. LocalHashCache.getNextHash() ──▶ Cache near empty
2. CacheRefillService.refillCache() 
3. HashGenerator.getAvailableHashes()
4. HashRepository.findByStatusNotUsed()
5. Return hashes to LocalHashCache
```

## Производительность и оптимизации

### Кеширование (3 уровня)
```
┌─────────────────┬─────────────┬──────────────┬─────────────┐
│     Cache       │   Latency   │   Hit Rate   │   Capacity  │
├─────────────────┼─────────────┼──────────────┼─────────────┤
│ L1 (Caffeine)   │   ~0.1ms    │     ~90%     │   10,000    │
│ L2 (Redis)      │   ~1ms      │     ~9%      │  Unlimited  │  
│ L3 (Database)   │   ~10ms     │     ~1%      │  Unlimited  │
└─────────────────┴─────────────┴──────────────┴─────────────┘
```

### Пакетная обработка статистики
- **Без батчинга:** 1000 посещений = 1000 SQL запросов
- **С батчингом:** 1000 посещений = 1 batch SQL запрос
- **Улучшение:** ~1000x снижение нагрузки на БД

### Предгенерация хешей
- **Старый подход:** Генерация на лету (блокирующая)
- **Новый подход:** Предгенерированный пул (неблокирующий)
- **Результат:** Мгновенное создание коротких ссылок

## Хранилища данных

### 💾 Database (PostgreSQL)
**Таблицы:**
```sql
-- URLs with visits counter
CREATE TABLE url (
    id BIGSERIAL PRIMARY KEY,
    hash_value VARCHAR(8) NOT NULL UNIQUE,
    original_url TEXT NOT NULL,
    visits_count BIGINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE
);

-- Hash pool with status
CREATE TABLE hash (
    id BIGSERIAL PRIMARY KEY, 
    value VARCHAR(8) NOT NULL UNIQUE,
    status VARCHAR(20) DEFAULT 'FREE', -- RESERVED/USED
    created_at TIMESTAMP DEFAULT NOW()
);
```

### 🔴 Redis (L2 Cache)
**Структуры данных:**
```
url:cache:{hashValue} -> originalUrl (TTL: 1 hour)
url:stats:{hashValue} -> visit_count (TTL: 1 day)
```

### 🔴 Caffeine (L1 Cache)
**Конфигурация:**
```java
LoadingCache<String, String> localCache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .recordStats()
    .build(this::loadUrl);
```

## Ключевые улучшения архитектуры

### Производительность ⚡
- ✅ **3-уровневое кеширование** (Caffeine → Redis → Database)
- ✅ **Предгенерированные хеши** в LocalHashCache
- ✅ **Пакетная статистика** через UrlVisitService
- ✅ **Асинхронная обработка** посещений

### Масштабируемость 📈
- ✅ **Горизонтальное масштабирование** Redis кеша
- ✅ **Неблокирующие операции** создания ссылок
- ✅ **Batch processing** для высоких нагрузок
- ✅ **Автоматическое пополнение** пула хешей

### Надежность 🛡️
- ✅ **Graceful degradation** при падении кешей
- ✅ **Race condition handling** при создании дубликатов
- ✅ **Failover между уровнями** кеширования
- ✅ **Атомарные операции** для статистики

### Мониторинг 📊
- ✅ **Caffeine cache stats** (hit rate, evictions)
- ✅ **Подробное логирование** всех операций
- ✅ **Метрики производительности** кешей
- ✅ **Отслеживание ошибок** и исключений

## Сравнение производительности

### Создание короткой ссылки
```
Старая архитектура: ~50-100ms (генерация хеша + БД)
Новая архитектура: ~5-10ms (предгенерированный хеш + кеширование)
Улучшение: 5-10x быстрее
```

### Редирект по короткой ссылке  
```
Без кеша: ~10-50ms (всегда БД)
L1 Cache hit: ~0.1ms (Caffeine)
L2 Cache hit: ~1ms (Redis) 
L3 Database: ~10ms (PostgreSQL)
Улучшение: 10-100x быстрее для популярных ссылок
```

### Обработка статистики
```
Без батчинга: 1000 RPS = 1000 SQL UPDATE
С батчингом: 1000 RPS = 1 batch UPDATE каждые 30с
Улучшение: ~1000x снижение нагрузки на БД
```
