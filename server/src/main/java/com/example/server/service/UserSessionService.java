package com.example.server.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserSessionService {
    private final Map<String, Set<String>> documentUsers = new ConcurrentHashMap<>();

    public void addUser(String docId, String username) {
        documentUsers.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(username);
    }

    public void removeUser(String docId, String username) {
        Set<String> users = documentUsers.get(docId);
        if (users != null) {
            users.remove(username);
            if (users.isEmpty()) {
                documentUsers.remove(docId);
            }
        }
    }

    public String[] getUsers(String docId) {
        return documentUsers.getOrDefault(docId, Set.of()).toArray(new String[0]);
    }

    public boolean hasUsers(String docId) {
        return documentUsers.containsKey(docId) && !documentUsers.get(docId).isEmpty();
    }
}
