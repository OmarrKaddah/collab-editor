package com.example.server.model;

import java.util.*;

public class CRDTDocument {

    private final CRDTCharacter head; // Root node
    private final Map<String, CRDTCharacter> idMap = new HashMap<>();
    private final Map<String, List<CRDTCharacter>> tree = new HashMap<>();

    public CRDTDocument() {
        head = new CRDTCharacter('\0', "HEAD", null, true);
        idMap.put("HEAD", head);
        tree.put("HEAD", new ArrayList<>());
    }

    public synchronized CRDTCharacter insert(CRDTCharacter newChar) {
        // if (idMap.containsKey(newChar.getId())) return null; // already exists
        insertCharacter(newChar);
        return newChar;
    }
    

    // ✅ External delete API for controller+
    public synchronized CRDTCharacter delete(String id) {
        CRDTCharacter ch = idMap.get(id);
        if (ch != null) {
            ch.setVisible(false);
        }
        return ch;
    }

    public synchronized void apply(CRDTMessage message) {
        if ("insert".equals(message.getType())) {
            insertCharacter(message.getCharacter());
        } else if ("delete".equals(message.getType())) {
            deleteCharacter(message.getCharacter().getId());
        }
        else{
            System.out.println("Error message type: " + message.getType());
        }
    }

    private void insertCharacter(CRDTCharacter newChar) {
        // if (idMap.containsKey(newChar.getId())) return;

        String parentId = newChar.getParentId();
        tree.putIfAbsent(parentId, new ArrayList<>());

        List<CRDTCharacter> siblings = tree.get(parentId);
        int insertIdx = 0;
        while (insertIdx < siblings.size() && siblings.get(insertIdx).getId().compareTo(newChar.getId()) < 0) {
            insertIdx++;
        }
        siblings.add(insertIdx, newChar);
        idMap.put(newChar.getId(), newChar);
        tree.put(newChar.getId(), new ArrayList<>());
    }

    private void deleteCharacter(String id) {
        CRDTCharacter ch = idMap.get(id);
        if (ch != null) ch.setVisible(false);
    }

    public synchronized String getVisibleText() {
        StringBuilder sb = new StringBuilder();
        dfsBuildText("HEAD", sb);
        return sb.toString();
    }

    private void dfsBuildText(String id, StringBuilder sb) {
        for (CRDTCharacter child : tree.getOrDefault(id, Collections.emptyList())) {
            if (child.isVisible() && child.getValue() != '\0') {
                sb.append(child.getValue());
            }
            dfsBuildText(child.getId(), sb);
        }
    }

    public List<CRDTCharacter> getAllCharacters() {
        List<CRDTCharacter> result = new ArrayList<>();
        flattenDFS("HEAD", result);
        return result;
    }

    private void flattenDFS(String id, List<CRDTCharacter> out) {
        for (CRDTCharacter ch : tree.getOrDefault(id, Collections.emptyList())) {
            out.add(ch);
            flattenDFS(ch.getId(), out);
        }
    }
}
