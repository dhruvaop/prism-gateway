package com.dhruva.airtribe.prism;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Scheduled cache retention. The daily purge is intentionally blunt: memory is
 * cleared and both cache tables are emptied, which keeps the Supabase/Postgres
 * free tier from accumulating abandoned prompts and responses.
 */
@Service
public class CacheMaintenanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheMaintenanceService.class);
    private final PrismService prism;

    @Value("${prism.cache.purge-enabled:true}")
    private boolean enabled;

    public CacheMaintenanceService(PrismService prism) {
        this.prism = prism;
    }

    @Scheduled(cron = "${prism.cache.purge-cron:0 0 17 * * *}",
            zone = "${prism.cache.purge-zone:Asia/Kolkata}")
    public void dailyPurge() {
        if (!enabled) {
            LOGGER.debug("cache_purge_skipped disabled=true");
            return;
        }
        Map<String, Object> result = prism.purgeCache();
        Object memory = result.get("memory");
        Object removed = memory instanceof Map<?, ?> map ? map.get("removed") : 0;
        LOGGER.info("cache_purge_scheduled deletedEntries={} deletedHits={} memoryRemoved={}",
                result.get("deleted_entries"), result.get("deleted_hits"), removed);
    }
}
