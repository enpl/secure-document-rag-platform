package com.sdv.identity.application;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DirectorySearchRateLimiter {
    private static final int LIMIT = 30;
    private static final long WINDOW_MS = 60_000L;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    public boolean allow(String subject) {
        long now = clock.millis();
        Window result = windows.compute(subject, (key, current) -> current == null || now - current.startedAt >= WINDOW_MS
                ? new Window(now, 1) : new Window(current.startedAt, current.count + 1));
        if (windows.size() > 10_000) windows.entrySet().removeIf(e -> now - e.getValue().startedAt >= WINDOW_MS);
        return result.count <= LIMIT;
    }

    private record Window(long startedAt, int count) { }
}
