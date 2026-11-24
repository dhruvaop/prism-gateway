package com.dhruva.airtribe.prism;

import java.util.List;
import java.util.Set;

/** Storage for the rolling provider health samples used by route ordering. */
interface ProviderHealthStore {
    void record(ProviderHealthMonitor.Sample sample);
    List<ProviderHealthMonitor.Sample> samples(String provider, long cutoffMillis);
    Set<String> providers();
    /** Removes samples older than the rolling window so the table stays bounded. */
    void prune(long cutoffMillis);
    void reset();
}
