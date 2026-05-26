package com.logslim.extraction;

import com.logslim.storage.Template;
import com.logslim.storage.TemplateDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Two-level template lookup:
 * 1. Caffeine LRU cache (in-memory, fast path) keyed by normalized pattern
 * 2. DB fallback on cache miss
 *
 * Also maintains a ConcurrentHashMap of pattern → template for O(1) candidate
 * retrieval by the TemplateMatcher.
 */
@Component
public class TemplateCache {

    private final Cache<String, Template> cache;
    private final TemplateDao templateDao;

    public TemplateCache(TemplateDao templateDao,
            @Value("${logslim.template.max-count:100000}") int maxCount) {
        this.templateDao = templateDao;
        this.cache = Caffeine.newBuilder().maximumSize(maxCount).build();
    }

    /** Look up by exact normalized pattern; DB fallback on miss. */
    public Optional<Template> get(String normalizedPattern) {
        Template cached = cache.getIfPresent(normalizedPattern);
        if (cached != null)
            return Optional.of(cached);

        Optional<Template> fromDb = templateDao.findByPattern(normalizedPattern);
        fromDb.ifPresent(t -> cache.put(normalizedPattern, t));
        return fromDb;
    }

    /** Store a newly created template in both cache and DB. */
    public Template put(Template template) {
        Template saved = templateDao.insert(template);
        cache.put(saved.getPattern(), saved);
        return saved;
    }

    /** Update cache after occurrence increment (keeps count in sync). */
    public void refresh(Template template) {
        template.setOccurrences(template.getOccurrences() + 1);
        cache.put(template.getPattern(), template);
        templateDao.incrementOccurrences(template.getId());
    }

    /** Update in-memory occurrence counts after a batch DB flush (no DB write). */
    public void refreshBatch(Map<Long, Long> deltas) {
        cache.asMap().forEach((pattern, template) -> {
            Long delta = deltas.get(template.getId());
            if (delta != null) {
                template.setOccurrences(template.getOccurrences() + delta);
            }
        });
    }

    /** Invalidate a single entry (e.g. after eviction). */
    public void invalidate(String pattern) {
        cache.invalidate(pattern);
    }

    public long cachedSize() {
        return cache.estimatedSize();
    }

    public void clearAll() {
        cache.invalidateAll();
    }
}
