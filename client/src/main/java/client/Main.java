package client;

import client.model.CRDTCharacter;
import client.model.CRDTMessage;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class Main extends Application {
    String connectUrl = "ws://localhost:8080/ws";

    private StompSession stompSession;
    private TextArea textArea;
    private String lastOperationType = "remote"; // or "none"
    private String documentId = null;
    private Runnable onConnectedCallback = null;
    private String importedParenId = null;
    private String lastImportedId = null;
    private String clientId = null;

    // Undo/Redo stacks
    private final Deque<CRDTMessage> undoStack = new ArrayDeque<>();
    private final Deque<CRDTMessage> redoStack = new ArrayDeque<>();
    private boolean isUndoRedoInProgress = false;

    private final AtomicLong lastTimestamp = new AtomicLong(0);

    private final List<CRDTCharacter> localVisibleChars = new ArrayList<>();

    // Helper method to generate random 5-digit number
    private String generateDocumentId() {
        Random random = new Random();
        int num = 10000 + random.nextInt(90000); // Generates between 10000 and 99999
        return String.valueOf(num);
    }

    @Override
    public void start(Stage primaryStage) {
        showLoginScreen(primaryStage);
    }

    private void showLoginScreen(Stage stage) {
        VBox loginScreen = new VBox(10);
        loginScreen.setStyle("-fx-padding: 20; -fx-alignment: center;");

        Text title = new Text("Enter Your Username");
        TextField usernameField = new TextField();
        usernameField.setPromptText("Max 10 characters");
        usernameField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.length() > 10) {
                usernameField.setText(oldVal);
            }
        });

        Button loginButton = new Button("Login");
        loginButton.setOnAction(e -> {
            String username = usernameField.getText().trim();
            if (!username.isEmpty()) {
                clientId = username;
                showDocumentSelectionScreen(stage);
            }
        });

        loginScreen.getChildren().addAll(title, usernameField, loginButton);
        Scene loginScene = new Scene(loginScreen, 300, 200);
        stage.setTitle("Login");
        stage.setScene(loginScene);
        stage.show();
    }

    private void showDocumentSelectionScreen(Stage stage) {
        VBox selectionScreen = new VBox(10);
        Text title = new Text("Collaborative Editor - " + clientId);

        Button createButton = new Button("Create New Document");
        createButton.setOnAction(e -> {
            documentId = generateDocumentId();
            System.out.println("Creating new document: " + documentId);

            connectToServer(); // connect first so you can send

            // Delay a bit to ensure connection is established
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    if (stompSession != null && stompSession.isConnected()) {
                        String destination = "/app/create/" + documentId;
                        System.out.println("Requesting creation at: " + destination);
                        stompSession.send(destination, null);
                    }

                    // UI update (JavaFX must run on main thread)
                    Platform.runLater(() -> {
                        showEditorScreen(stage);
                        localVisibleChars.clear();
                        updateTextFromCRDT();
                    });
                }
            }, 500); // 0.5s delay
        });

        // New Import Button
        Button importButton = new Button("Import Text File");
        importButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Open Text File");
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Text Files", "*.txt"));
            File selectedFile = fileChooser.showOpenDialog(stage);

            if (selectedFile != null) {
                try {
                    String content = new String(Files.readAllBytes(selectedFile.toPath()));
                    documentId = generateDocumentId();

                    // ✅ Set the import logic as a callback to run after connection is ready
                    onConnectedCallback = () -> {
                        localVisibleChars.clear();
                        updateTextFromCRDT();

                        for (char c : content.toCharArray()) {
                            if (c >= 32) { // Only insert printable characters
                                if (importedParenId == null) {
                                    importedParenId = "HEAD"; // First character has no parent
                                } else {
                                    importedParenId = lastImportedId;
                                }

                                String newId = generateUniqueId();
                                lastImportedId = newId;
                                CRDTCharacter newChar = new CRDTCharacter(c, newId, importedParenId, true);
                                localVisibleChars.add(newChar);

                                System.out.println("New char: " + c + " -> " + newId);
                                System.out.println("Parent ID: " + importedParenId);

                                // Send insert message to server
                                CRDTMessage msg = new CRDTMessage("insert", newChar);
                                sendMessage(msg);
                                System.out.println("Imported char: " + c + " -> " + newId);
                                try {
                                    Thread.sleep(20); // Optional delay for smoother import
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt(); // Restore interrupted status
                                    System.err.println("Thread was interrupted: " + ex.getMessage());
                                }
                            }
                        }

                        lastOperationType = "remote";
                        updateTextFromCRDT();
                    };

                    // ✅ Show the editor, which will also call connectToServer() internally
                    showEditorScreen(stage);

                } catch (IOException ex) {
                    showError("Failed to read file: " + ex.getMessage());
                }
            }
        });

        Button joinButton = new Button("Join Existing Document");
        TextField docIdField = new TextField();
        docIdField.setPromptText("Enter 5-digit Document ID with V/E suffix");

        joinButton.setOnAction(e -> {
            String inputId = docIdField.getText().trim();
            if (!inputId.matches("\\d{5}[VE]")) {
                showError("Please enter a valid 5-digit ID ending with V or E");
                return;
            }

            documentId = inputId.substring(0, 5);
            String mode = inputId.endsWith("E") ? "editor" : "viewer";

            WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
            stompClient.setMessageConverter(new MappingJackson2MessageConverter());

            stompClient.connect("ws://localhost:8080/ws", new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                    System.out.println("Checking server for document: " + documentId);
                    // Subscribe for the response
                    session.subscribe("/user/queue/exists", new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return Boolean.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            boolean exists = (Boolean) payload;
                            Platform.runLater(() -> {
                                if (!exists) {
                                    showError("❌ Document not found. Please check your ID.");
                                    // optionally reset the view here if needed
                                } else {
                                    if (inputId.endsWith("E")) {
                                        showEditorScreen(stage);
                                    } else {
                                        showViewerScreen(stage);
                                    }
                                }
                            });
                        }
                    });

                    // Request the existence check
                    session.send("/app/exists/" + documentId, null);
                }
            });
        });

        selectionScreen.getChildren().addAll(title, createButton, importButton, docIdField, joinButton);
        selectionScreen.setStyle("-fx-padding: 20; -fx-alignment: center;");

        Scene selectionScene = new Scene(selectionScreen, 400, 300);
        stage.setTitle("Collaborative Editor - " + clientId);
        stage.setScene(selectionScene);
    }

    private void showViewerScreen(Stage stage) {
        textArea = new TextArea();
        textArea.setPromptText("Waiting for document content...");
        textArea.setWrapText(true);
        textArea.setEditable(false); // Viewer cannot edit

        // No document code shown in viewer mode
        VBox root = new VBox(textArea);
        Scene viewerScene = new Scene(root, 600, 400);
        stage.setTitle("Viewing Document | User: " + clientId); // No doc ID in title
        stage.setScene(viewerScene);

        connectToServer();
    }

    private void showEditorScreen(Stage stage) {
        textArea = new TextArea();
        textArea.setPromptText("Start typing...");
        textArea.setWrapText(true);
        textArea.setEditable(true);

        // Create copyable document code display (only in editor mode)
        TextField codeVField = new TextField(documentId + "V");
        codeVField.setEditable(false);
        Button copyVButton = new Button("Copy");
        copyVButton.setOnAction(e -> {
            copyToClipboard(codeVField.getText());
            showCopiedNotification("Viewer code copied!");
        });

        TextField codeEField = new TextField(documentId + "E");
        codeEField.setEditable(false);
        Button copyEButton = new Button("Copy");
        copyEButton.setOnAction(e -> {
            copyToClipboard(codeEField.getText());
            showCopiedNotification("Editor code copied!");
        });

        HBox codeVBox = new HBox(5, new Label("Viewer Code:"), codeVField, copyVButton);
        HBox codeEBox = new HBox(5, new Label("Editor Code:"), codeEField, copyEButton);

        VBox codeBox = new VBox(5, codeVBox, codeEBox);
        codeBox.setStyle("-fx-padding: 10; -fx-background-color: #f0f0f0;");

        VBox root = new VBox(codeBox, textArea);
        Scene editorScene = new Scene(root, 600, 400);
        stage.setTitle("Editing Document: " + documentId + " | User: " + clientId);
        stage.setScene(editorScene);

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

        // ➕ ADD this block for paste handling
        textArea.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode().toString().equals("V")) {
                System.out.println("Ctrl+V pressed");
                String pastedText = getClipboardText();
                if (pastedText != null) {
                    int caretPos = textArea.getCaretPosition();
                    pasteTextAtCaret(pastedText, caretPos);
                    event.consume(); // prevent default pasting
                }
            }
        });

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

    private void showError(String message) {
        Platform.runLater(() -> {
            Text errorText = new Text(message);
            Scene errorScene = new Scene(new VBox(errorText), 250, 100);
            Stage errorStage = new Stage();
            errorStage.setTitle("Invalid ID");
            errorStage.setScene(errorScene);
            errorStage.show();
        });
    }

    // Helper method to copy text to clipboard
    private void copyToClipboard(String text) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);
    }

    // Helper method to show a copied notification
    private void showCopiedNotification(String message) {
        Stage notificationStage = new Stage();
        notificationStage.initStyle(StageStyle.UTILITY);
        notificationStage.setTitle("Copied");

        Label label = new Label(message);
        label.setStyle("-fx-padding: 10;");

        Scene scene = new Scene(label);
        notificationStage.setScene(scene);
        notificationStage.setWidth(200);
        notificationStage.setHeight(100);
        notificationStage.show();

        // Auto-close after 1.5 seconds
        PauseTransition delay = new PauseTransition(Duration.seconds(0.5));
        delay.setOnFinished(e -> notificationStage.close());
        delay.play();
    }

    private void connectToServer() {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        System.out.println("Connecting to document: " + documentId); // Debug log

        ListenableFuture<StompSession> future = stompClient.connect(connectUrl, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                stompSession = session;
                String subscriptionTopic = "/topic/updates/" + documentId;
                System.out.println("Subscribing to: " + subscriptionTopic);

                session.subscribe(subscriptionTopic, new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return CRDTMessage.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        System.out.println("Received update for doc " + documentId);
                        if (payload instanceof CRDTMessage msg) {
                            Platform.runLater(() -> applyServerUpdate(msg));
                        }
                    }
                });

                session.subscribe("/user/queue/sync", new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return CRDTMessage[].class; // ✅ Fix: use array
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        CRDTMessage[] messages = (CRDTMessage[]) payload; // ✅ cast to array
                        Platform.runLater(() -> {
                            for (CRDTMessage msg : messages) {
                                applyServerUpdate(msg);
                            }
                        });
                    }
                });
                if (onConnectedCallback != null) {
                    Platform.runLater(onConnectedCallback);
                    onConnectedCallback = null; // prevent repeated calls
                }

                String syncDest = "/app/sync/" + documentId;
                System.out.println("📨 Requesting sync: " + syncDest);
                session.send(syncDest, null);

            }

            @Override
            public void handleTransportError(StompSession session, Throwable ex) {
                System.err.println("Transport error: " + ex.getMessage());
                ex.printStackTrace();
            }

            @Override
            public void handleException(StompSession session, StompCommand command,
                    StompHeaders headers, byte[] payload, Throwable ex) {
                System.err.println("STOMP exception: " + ex.getMessage());
                ex.printStackTrace();
            }
        });

        future.addCallback(
                result -> System.out.println("✅ Connected to server. Document ID: " + documentId),
                ex -> System.err.println("❌ Failed to connect: " + ex.getMessage()));
    }

    private String getClipboardText() {
        return javafx.scene.input.Clipboard.getSystemClipboard().getString();
    }

    private String getParentIdFromVisibleCaret(int targetIndex) {
        if (targetIndex < 0)
            return "HEAD";

        int visibleIdx = 0;
        for (CRDTCharacter ch : localVisibleChars) {
            if (!ch.isVisible())
                continue;
            if (visibleIdx == targetIndex)
                return ch.getId();
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
            if (c < 32 && c != '\n')
                continue;
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
        if (isUndoRedoInProgress || msg == null)
            return;

        CRDTCharacter ch = msg.getCharacter();
        if (ch == null)
            return;

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
            if (existing.getId().equals(ch.getId()))
                return;
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
                if (visibleCount == oldPos)
                    break;
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
            case "insert":
                return Math.min(anchor, textLength);
            case "delete":
                return Math.max(anchor, 0);
            case "remote":
                return Math.min(anchor, textLength);
            default:
                return anchor;
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
        if (caretPos == 0 || localVisibleChars.isEmpty())
            return "HEAD";

        int visibleCount = 0;
        String parentId = "HEAD";

        for (CRDTCharacter ch : localVisibleChars) {
            if (ch.isVisible()) {
                visibleCount++;
                if (visibleCount == caretPos)
                    break;
                parentId = ch.getId();
            }
        }

        return parentId;
    }

    private void sendDelete() {
        int caretPos = textArea.getCaretPosition();
        CRDTCharacter toDelete = findCharacterAtPosition(caretPos);
        if (toDelete == null)
            return;

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
        if (undoStack.isEmpty())
            return;

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
        if (redoStack.isEmpty())
            return;

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
        if (original == null || original.getCharacter() == null)
            return null;

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
        if (msg == null || msg.getCharacter() == null)
            return;

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
        System.out.println("Will Send Message");
        if (stompSession != null && stompSession.isConnected()) {
            System.out.println("Sending message: " + msg.getType() + " with ID: " + msg.getCharacter().getId());
            // Add document ID to the send destination
            String sendDestination = "/app/edit/" + documentId;
            System.out.println("Sending to: " + sendDestination);
            stompSession.send(sendDestination, msg);
        }
    }

    public static void main(String[] args) {
        launch();
    }
}