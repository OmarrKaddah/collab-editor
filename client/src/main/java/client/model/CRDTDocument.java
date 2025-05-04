package client.model;

import java.util.*;

public class CRDTDocument {
    private final CRDTCharacter head;
    private final Map<String, CRDTCharacter> idMap = new HashMap<>();
    private final Map<String, List<CRDTCharacter>> tree = new HashMap<>();
    private List<CRDTCharacter> linearCache;
    private boolean cacheDirty = true;

    public CRDTDocument() {
        this.head = new CRDTCharacter('\0', "HEAD", null, true);
        this.idMap.put("HEAD", head);
        this.tree.put("HEAD", new ArrayList<>());
    }

    // Core CRDT operations
    public synchronized void applyInsert(CRDTCharacter newChar) {
        if (idMap.containsKey(newChar.getId()))
            return;

        String parentId = newChar.getParentId() != null ? newChar.getParentId() : "HEAD";
        tree.putIfAbsent(parentId, new ArrayList<>());

        // Insert in sorted order by ID
        List<CRDTCharacter> siblings = tree.get(parentId);
        int pos = 0;
        while (pos < siblings.size() && siblings.get(pos).getId().compareTo(newChar.getId()) < 0) {
            pos++;
        }
        siblings.add(pos, newChar);
        idMap.put(newChar.getId(), newChar);
        tree.putIfAbsent(newChar.getId(), new ArrayList<>());
        cacheDirty = true;
    }

    public synchronized void applyDelete(String charId) {
        CRDTCharacter ch = idMap.get(charId);
        if (ch != null) {
            ch.setVisible(false);
            cacheDirty = true;
        }
    }

    // Document state access
    public synchronized String getVisibleText() {
        rebuildCacheIfNeeded();
        StringBuilder sb = new StringBuilder();
        for (CRDTCharacter ch : linearCache) {
            if (ch.isVisible() && ch.getValue() != '\0') {
                sb.append(ch.getValue());
            }
        }
        return sb.toString();
    }

    // In CRDTDocument.java
    public synchronized void apply(DocumentUpdateMessage message) {
        cacheDirty = true; // Invalidate cache on changes

        if ("insert".equals(message.getType())) {
            applyInsert(message.getCharacter());
        } else if ("delete".equals(message.getType())) {
            applyDelete(message.getCharacter().getId());
        }
    }

    public synchronized String findLastVisibleCharId() {
        List<CRDTCharacter> allChars = getAllCharacters();
        String lastVisibleId = "HEAD"; // Default to root if no visible chars

        // Iterate backwards to find last visible character
        for (int i = allChars.size() - 1; i >= 0; i--) {
            CRDTCharacter ch = allChars.get(i);
            if (ch.isVisible() && ch.getValue() != '\0') {
                lastVisibleId = ch.getId();
                break;
            }
        }
        return lastVisibleId;
    }

    public synchronized String findCharIdAtPosition(int position) {
        if (position < 0)
            return null;

        rebuildCacheIfNeeded();
        int currentPos = 0;
        for (CRDTCharacter ch : linearCache) {
            if (ch.isVisible() && ch.getValue() != '\0') {
                if (currentPos == position) {
                    return ch.getId();
                }
                currentPos++;
            }
        }
        return null;
    }

    public synchronized int findPositionOfChar(String charId) {
        rebuildCacheIfNeeded();
        int pos = 0;
        for (CRDTCharacter ch : linearCache) {
            if (ch.isVisible() && ch.getValue() != '\0') {
                if (ch.getId().equals(charId)) {
                    return pos;
                }
                pos++;
            }
        }
        return -1;
    }

    // Cache management
    private void rebuildCacheIfNeeded() {
        if (cacheDirty || linearCache == null) {
            linearCache = new ArrayList<>();
            flattenDFS("HEAD", linearCache);
            cacheDirty = false;
        }
    }

    private void flattenDFS(String nodeId, List<CRDTCharacter> result) {
        for (CRDTCharacter child : tree.getOrDefault(nodeId, Collections.emptyList())) {
            result.add(child);
            flattenDFS(child.getId(), result);
        }
    }

    // For network synchronization
    public synchronized List<CRDTCharacter> getAllCharacters() {
        rebuildCacheIfNeeded();
        return new ArrayList<>(linearCache);
    }

    public synchronized void mergeRemoteOperations(List<CRDTCharacter> remoteChars) {
        for (CRDTCharacter remoteChar : remoteChars) {
            if (!idMap.containsKey(remoteChar.getId())) {
                applyInsert(remoteChar);
            } else if (!remoteChar.isVisible()) {
                applyDelete(remoteChar.getId());
            }
        }
    }
}