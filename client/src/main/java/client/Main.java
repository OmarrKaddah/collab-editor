package client;

import client.model.CRDTCharacter;
import client.model.CRDTMessage;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class Main extends Application {

    private StompSession stompSession;
    private TextArea textArea;
    private String lastOperationType = "remote"; // or "none"
    
    // Undo/Redo stacks
    private final Deque<CRDTMessage> undoStack = new ArrayDeque<>();
    private final Deque<CRDTMessage> redoStack = new ArrayDeque<>();
    private boolean isUndoRedoInProgress = false;

    private final String clientId = UUID.randomUUID().toString().substring(0, 6);
    private final AtomicLong lastTimestamp = new AtomicLong(0);

    private final List<CRDTCharacter> localVisibleChars = new ArrayList<>();

    @Override
    public void start(Stage stage) {
        textArea = new TextArea();
        textArea.setPromptText("Start typing...");
        textArea.setWrapText(true);
        textArea.setEditable(true);

        VBox root = new VBox(textArea);
        Scene scene = new Scene(root, 600, 400);
        stage.setTitle("Collaborative Editor (" + clientId + ")");
        stage.setScene(scene);
        stage.show();

        connectToServer();

        // Handle input
        textArea.setOnKeyTyped(event -> {
            if (isUndoRedoInProgress) {
                event.consume();
                return;
            }
            
            String typed = event.getCharacter();
            event.consume();
        
            if ("\b".equals(typed)) {
                sendDelete();
            } else if (typed.equals("\r") || typed.equals("\n")) {
                sendInsert('\n');
            } else if (typed.length() > 0 && typed.charAt(0) >= 32) {
                sendInsert(typed.charAt(0));
            }
        });
        
        // Handle Ctrl+V for paste and Ctrl+Z/Y for undo/redo
        textArea.setOnKeyPressed(event -> handleKeyPress(event));
        
        Platform.runLater(() -> textArea.requestFocus());
    }

    private void handleKeyPress(KeyEvent event) {
        if (isUndoRedoInProgress) {
            event.consume();
            return;
        }
        
        if (event.isControlDown()) {
            switch (event.getCode()) {
                case V:
                    handlePaste();
                    event.consume();
                    break;
                case Z:
                    if (!event.isShiftDown()) {
                        undo();
                        event.consume();
                    }
                    break;
                case Y:
                    redo();
                    event.consume();
                    break;
            }
        }
    }

    private void handlePaste() {
        String pastedText = getClipboardText();
        if (pastedText != null && !pastedText.isEmpty()) {
            int caretPos = textArea.getCaretPosition();
            pasteTextAtCaret(pastedText, caretPos);
        }
    }

    private void connectToServer() {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        ListenableFuture<StompSession> future = stompClient.connect("ws://localhost:8080/ws", new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                stompSession = session;

                session.subscribe("/topic/updates", new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return CRDTMessage.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        if (payload instanceof CRDTMessage msg) {
                            Platform.runLater(() -> applyServerUpdate(msg));
                        }
                    }
                });
            }

            @Override
            public void handleTransportError(StompSession session, Throwable ex) {
                ex.printStackTrace();
            }
        });

        future.addCallback(
                result -> System.out.println("✅ Connected to server."),
                ex -> System.err.println("❌ Failed to connect: " + ex.getMessage())
        );
    }

    private String getClipboardText() {
        return javafx.scene.input.Clipboard.getSystemClipboard().getString();
    }

    private String getParentIdFromVisibleCaret(int targetIndex) {
        if (targetIndex < 0) return "HEAD";
    
        int visibleIdx = 0;
        for (CRDTCharacter ch : localVisibleChars) {
            if (!ch.isVisible()) continue;
            if (visibleIdx == targetIndex) return ch.getId();
            visibleIdx++;
        }
    
        // Fallback to last visible char
        for (int i = localVisibleChars.size() - 1; i >= 0; i--) {
            if (localVisibleChars.get(i).isVisible()) {
                return localVisibleChars.get(i).getId();
            }
        }
    
        return "HEAD";
    }
    
    private void pasteTextAtCaret(String pastedText, int caretPos) {
        String parentId = getParentIdFromVisibleCaret(caretPos - 1); 
        List<CRDTMessage> pasteOperations = new ArrayList<>();
        
        for (char c : pastedText.toCharArray()) {
            if (c < 32 && c != '\n') continue;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                e.printStackTrace();
            }
            parentId = sendInsertAt(c, parentId, pasteOperations);
        }

        if (!pasteOperations.isEmpty()) {
            CRDTMessage batchMsg = new CRDTMessage("batch", null);
            batchMsg.setBatchOperations(pasteOperations);
            undoStack.push(batchMsg);
            redoStack.clear();
        }
        
        lastOperationType = "insert";
        updateTextFromCRDT();
    }
    
    private String sendInsertAt(char c, String parentId, List<CRDTMessage> operationList) {
        String newId = generateUniqueId();
        CRDTCharacter newChar = new CRDTCharacter(c, newId, parentId, true);

        int insertIdx = findInsertIndex(parentId);
        localVisibleChars.add(insertIdx, newChar);
        
        CRDTMessage msg = new CRDTMessage("insert", newChar);
        sendMessage(msg);
        
        if (operationList != null) {
            operationList.add(msg);
        } else {
            undoStack.push(msg);
            redoStack.clear();
        }
        
        return newId;
    }

    private int findInsertIndex(String parentId) {
        for (int i = 0; i < localVisibleChars.size(); i++) {
            if (localVisibleChars.get(i).getId().equals(parentId)) {
                return i + 1;
            }
        }
        return localVisibleChars.size();
    }

    private void applyServerUpdate(CRDTMessage msg) {
        if (isUndoRedoInProgress || msg == null) return;
        
        CRDTCharacter ch = msg.getCharacter();
        if (ch == null) return;
    
        if ("insert".equals(msg.getType())) {
            handleInsertOperation(ch);
        } else if ("delete".equals(msg.getType())) {
            handleDeleteOperation(ch);
        }
        
        lastOperationType = "remote";
        updateTextFromCRDT();
    }
    
    private void handleInsertOperation(CRDTCharacter ch) {
        // Check for duplicates
        for (CRDTCharacter existing : localVisibleChars) {
            if (existing.getId().equals(ch.getId())) return;
        }

        int insertIdx = findInsertIndex(ch.getParentId());
        localVisibleChars.add(insertIdx, ch);
    }
    
    private void handleDeleteOperation(CRDTCharacter ch) {
        localVisibleChars.removeIf(c -> c.getId().equals(ch.getId()));
    }

    private void updateTextFromCRDT() {
        int oldCaretPos = textArea.getCaretPosition();
        int visibleCaretAnchor = calculateVisibleCaretPosition(oldCaretPos);
        
        String visibleText = buildVisibleText();
        textArea.setText(visibleText);
        
        int newCaretPos = calculateNewCaretPosition(visibleCaretAnchor, visibleText.length());
        textArea.positionCaret(newCaretPos);
        
        lastOperationType = "none";
        printVisibleCRDTState();
    }
    
    private int calculateVisibleCaretPosition(int oldPos) {
        int visibleCount = 0;
        for (CRDTCharacter ch : localVisibleChars) {
            if (ch.isVisible()) {
                if (visibleCount == oldPos) break;
                visibleCount++;
            }
        }
        return visibleCount;
    }
    
    private String buildVisibleText() {
        StringBuilder sb = new StringBuilder();
        for (CRDTCharacter ch : localVisibleChars) {
            if (ch.isVisible()) {
                sb.append(ch.getValue());
            }
        }
        return sb.toString();
    }
    
    private int calculateNewCaretPosition(int anchor, int textLength) {
        switch (lastOperationType) {
            case "insert": return Math.min(anchor, textLength);
            case "delete": return Math.max(anchor, 0);
            case "remote": return Math.min(anchor, textLength);
            default: return anchor;
        }
    }
        
    private void printVisibleCRDTState() {
        System.out.println("📜 Visible CRDT Content:");
        int i = 0;
        for (CRDTCharacter ch : localVisibleChars) {
            if (ch.isVisible()) {
                String value = ch.getValue() == '\n' ? "\\n" : String.valueOf(ch.getValue());
                System.out.printf("[%02d] '%s' (id=%s, parent=%s)\n", i++, value, ch.getId(), ch.getParentId());
            }
        }
        System.out.println("---");
    }
    
    private void sendInsert(char c) {
        int caretPos = textArea.getCaretPosition();
        String parentId = findParentIdForInsert(caretPos);
        
        String newId = generateUniqueId();
        CRDTCharacter newChar = new CRDTCharacter(c, newId, parentId, true);

        int insertIdx = findInsertIndex(parentId);
        localVisibleChars.add(insertIdx, newChar);

        CRDTMessage msg = new CRDTMessage("insert", newChar);
        sendMessage(msg);
        undoStack.push(msg);
        redoStack.clear();
        lastOperationType = "insert";
        updateTextFromCRDT();
    }

    private String findParentIdForInsert(int caretPos) {
        if (caretPos == 0 || localVisibleChars.isEmpty()) return "HEAD";
        
        int visibleCount = 0;
        String parentId = "HEAD";
        
        for (CRDTCharacter ch : localVisibleChars) {
            if (ch.isVisible()) {
                visibleCount++;
                if (visibleCount == caretPos) break;
                parentId = ch.getId();
            }
        }
        
        return parentId;
    }

    private void sendDelete() {
        int caretPos = textArea.getCaretPosition();
        CRDTCharacter toDelete = findCharacterAtPosition(caretPos);
        if (toDelete == null) return;

        int deleteIndex = localVisibleChars.indexOf(toDelete);
        localVisibleChars.remove(deleteIndex);
        
        CRDTCharacter deleteChar = new CRDTCharacter('\0', toDelete.getId(), null, false);
        CRDTMessage msg = new CRDTMessage("delete", deleteChar);
        sendMessage(msg);
        undoStack.push(msg);
        redoStack.clear();
        lastOperationType = "delete";
        updateTextFromCRDT();
    }

    private CRDTCharacter findCharacterAtPosition(int caretPos) {
        int visibleCount = 0;
        for (CRDTCharacter ch : localVisibleChars) {
            if (ch.isVisible()) {
                if (visibleCount == caretPos) {
                    return ch;
                }
                visibleCount++;
            }
        }
        return null;
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        
        isUndoRedoInProgress = true;
        try {
            CRDTMessage lastAction = undoStack.pop();
            
            if ("batch".equals(lastAction.getType())) {
                undoBatchOperation(lastAction);
            } else {
                undoSingleOperation(lastAction);
            }
            
            updateTextFromCRDT();
        } finally {
            isUndoRedoInProgress = false;
        }
    }

    private void undoBatchOperation(CRDTMessage batch) {
        List<CRDTMessage> operations = batch.getBatchOperations();
        if (operations != null) {
            for (int i = operations.size() - 1; i >= 0; i--) {
                CRDTMessage msg = operations.get(i);
                if (msg != null) {
                    CRDTMessage inverse = createInverseOperation(msg);
                    applyLocalOperation(inverse);
                    redoStack.push(msg);
                }
            }
        }
    }

    private void undoSingleOperation(CRDTMessage msg) {
        CRDTMessage inverse = createInverseOperation(msg);
        applyLocalOperation(inverse);
        redoStack.push(msg);
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        
        isUndoRedoInProgress = true;
        try {
            CRDTMessage lastUndone = redoStack.pop();
            
            if ("batch".equals(lastUndone.getType())) {
                redoBatchOperation(lastUndone);
            } else {
                redoSingleOperation(lastUndone);
            }
            
            updateTextFromCRDT();
        } finally {
            isUndoRedoInProgress = false;
        }
    }

    private void redoBatchOperation(CRDTMessage batch) {
        List<CRDTMessage> operations = batch.getBatchOperations();
        if (operations != null) {
            for (CRDTMessage msg : operations) {
                if (msg != null) {
                    applyLocalOperation(msg);
                    undoStack.push(msg);
                }
            }
        }
    }

    private void redoSingleOperation(CRDTMessage msg) {
        applyLocalOperation(msg);
        undoStack.push(msg);
    }

    private CRDTMessage createInverseOperation(CRDTMessage original) {
        if (original == null || original.getCharacter() == null) return null;
        
        if ("insert".equals(original.getType())) {
            CRDTCharacter ch = original.getCharacter();
            return new CRDTMessage("delete", new CRDTCharacter('\0', ch.getId(), null, false));
        } else if ("delete".equals(original.getType())) {
            CRDTCharacter ch = original.getCharacter();
            // We need to restore the original character properties
            CRDTCharacter originalChar = findOriginalCharacter(ch.getId());
            if (originalChar != null) {
                return new CRDTMessage("insert", originalChar);
            }
        }
        return null;
    }

    private CRDTCharacter findOriginalCharacter(String id) {
        for (CRDTCharacter ch : localVisibleChars) {
            if (ch.getId().equals(id)) {
                return new CRDTCharacter(ch.getValue(), ch.getId(), ch.getParentId(), true);
            }
        }
        return null;
    }

    private void applyLocalOperation(CRDTMessage msg) {
        if (msg == null || msg.getCharacter() == null) return;
        
        CRDTCharacter ch = msg.getCharacter();
        if ("insert".equals(msg.getType())) {
            int insertIdx = findInsertIndex(ch.getParentId());
            localVisibleChars.add(insertIdx, ch);
            sendMessage(msg);
        } else if ("delete".equals(msg.getType())) {
            localVisibleChars.removeIf(c -> c.getId().equals(ch.getId()));
            sendMessage(msg);
        }
    }

    private String generateUniqueId() {
        long now = System.currentTimeMillis();
        if (now <= lastTimestamp.get()) {
            now = lastTimestamp.incrementAndGet();
        } else {
            lastTimestamp.set(now);
        }
        return clientId + "_" + now;
    }

    private void sendMessage(CRDTMessage msg) {
        if (stompSession != null && stompSession.isConnected() && msg != null) {
            stompSession.send("/app/edit", msg);
        }
    }

    public static void main(String[] args) {
        launch();
    }
}