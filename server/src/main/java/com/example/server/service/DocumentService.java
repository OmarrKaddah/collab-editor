package com.example.server.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.example.server.model.CRDTDocument;

@Service
public class DocumentService {
    private final Map<String, CRDTDocument> documents = new ConcurrentHashMap<>();

    public CRDTDocument getDocument(String docId) {
        return documents.computeIfAbsent(docId, k -> new CRDTDocument());
    }

    public void removeDocument(String docId) {
        documents.remove(docId);
    }

    public boolean exists(String docId) {
        return documents.containsKey(docId);
    }
}