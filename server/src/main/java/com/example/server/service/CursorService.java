package com.example.server.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CursorService {

    // Map<docId, Map<username, position>>
    private final Map<String, Map<String, Integer>> cursorMap = new ConcurrentHashMap<>();

    public void updateCursor(String docId, String username, int position) {
        cursorMap.computeIfAbsent(docId, k -> new ConcurrentHashMap<>())
                .put(username, position);
    }

    public Integer getCursor(String docId, String username) {
        return cursorMap.getOrDefault(docId, Map.of()).get(username);
    }

    public Map<String, Integer> getCursorsForDoc(String docId) {
        return cursorMap.getOrDefault(docId, Map.of());
    }

    public void removeCursor(String docId, String username) {
        Map<String, Integer> map = cursorMap.get(docId);
        if (map != null) {
            map.remove(username);
            if (map.isEmpty()) {
                cursorMap.remove(docId);
            }
        }
    }
}
