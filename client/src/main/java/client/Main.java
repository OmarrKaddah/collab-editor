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
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import javafx.util.Duration;

import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import javafx.util.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class Main extends Application {

    private StompSession stompSession;
    private TextArea textArea;
    private String lastOperationType = "remote"; // or "none"
    private String documentId = null;

    private String clientId = null;
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
            showEditorScreen(stage);
        });

        Button joinButton = new Button("Join Existing Document");
        TextField docIdField = new TextField();
        docIdField.setPromptText("Enter 5-digit Document ID with V/E suffix");

        joinButton.setOnAction(e -> {
            String inputId = docIdField.getText().trim();
            if (inputId.matches("\\d{5}[VE]")) {
                documentId = inputId.substring(0, 5);
                if (inputId.endsWith("E")) {
                    showEditorScreen(stage);
                } else {
                    showViewerScreen(stage);
                }
            } else {
                showError("Please enter a valid 5-digit ID ending with V or E");
            }
        });

        selectionScreen.getChildren().addAll(title, createButton, docIdField, joinButton);
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
            String typed = event.getCharacter();
            event.consume();

            if ("\b".equals(typed)) {
                sendDelete();
            } else if (typed.length() > 0 && typed.charAt(0) >= 32) {
                sendInsert(typed.charAt(0));
            }
        });

        Platform.runLater(() -> textArea.requestFocus());
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

        String connectUrl = "ws://localhost:8080/ws";
        if (documentId != null) {
            connectUrl += "?docId=" + documentId;
        }

        ListenableFuture<StompSession> future = stompClient.connect(connectUrl, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                stompSession = session;

                session.subscribe("/topic/updates/" + documentId, new StompFrameHandler() {
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
                result -> System.out.println("✅ Connected to server. Document ID: " + documentId),
                ex -> System.err.println("❌ Failed to connect: " + ex.getMessage()));
    }

    private void applyServerUpdate(CRDTMessage msg) {
        CRDTCharacter ch = msg.getCharacter();

        if ("insert".equals(msg.getType())) {
            for (CRDTCharacter existing : localVisibleChars) {
                if (existing.getId().equals(ch.getId()))
                    return; // avoid duplicates
            }

            // ✅ Find parent index
            int insertIdx = 0;
            for (int i = 0; i < localVisibleChars.size(); i++) {
                if (localVisibleChars.get(i).getId().equals(ch.getParentId())) {
                    insertIdx = i + 1;
                    break;
                }
            }

            // ✅ Insert right after parent
            localVisibleChars.add(insertIdx, ch);

        } else if ("delete".equals(msg.getType())) {
            localVisibleChars.removeIf(c -> c.getId().equals(ch.getId()));
        }
        lastOperationType = "remote";
        updateTextFromCRDT();
    }

    private void updateTextFromCRDT() {
        int oldCaretPos = textArea.getCaretPosition();

        // Count how many visible characters existed before caret
        int visibleCaretAnchor = 0;
        int visibleIndex = 0;

        for (CRDTCharacter ch : localVisibleChars) {
            if (ch.isVisible()) {
                if (visibleIndex == oldCaretPos)
                    break;
                visibleCaretAnchor++;
                visibleIndex++;
            }
        }

        // Rebuild visible text
        StringBuilder sb = new StringBuilder();
        for (CRDTCharacter ch : localVisibleChars) {
            if (ch.isVisible()) {
                sb.append(ch.getValue());
            }
        }

        textArea.setText(sb.toString());

        int newCaretPos = visibleCaretAnchor;

        switch (lastOperationType) {
            case "insert" -> newCaretPos = Math.min(visibleCaretAnchor, sb.length());
            case "delete" -> newCaretPos = Math.max(visibleCaretAnchor, 0);
            case "remote" -> newCaretPos = Math.min(visibleCaretAnchor, sb.length());
        }

        textArea.positionCaret(newCaretPos);
        lastOperationType = "none";
    }

    private void sendInsert(char c) {
        int caretPos = textArea.getCaretPosition();

        // Find the parent character based on visible text (not index)
        String parentId;
        if (caretPos == 0 || localVisibleChars.isEmpty()) {
            parentId = "HEAD";
        } else {
            // Get visible characters only
            int visibleCount = 0;
            for (CRDTCharacter ch : localVisibleChars) {
                if (ch.isVisible())
                    visibleCount++;
                if (visibleCount == caretPos) {
                    // We've reached the caretPos; previous visible is the parent
                    break;
                }
            }

            // Get the (caretPos - 1)'th visible character as parent
            visibleCount = 0;
            parentId = "HEAD";
            for (CRDTCharacter ch : localVisibleChars) {
                if (ch.isVisible()) {
                    visibleCount++;
                    if (visibleCount == caretPos) {
                        break;
                    }
                    parentId = ch.getId(); // this becomes the parent
                }
            }
        }

        String newId = generateUniqueId();
        CRDTCharacter newChar = new CRDTCharacter(c, newId, parentId, true);

        // Find actual insert index in full localVisibleChars list (not visible ones)
        int insertIdx = 0;
        for (int i = 0; i < localVisibleChars.size(); i++) {
            if (localVisibleChars.get(i).getId().equals(parentId)) {
                insertIdx = i + 1;
                break;
            }
        }

        localVisibleChars.add(insertIdx, newChar);

        CRDTMessage msg = new CRDTMessage("insert", newChar);
        sendMessage(msg);
        lastOperationType = "insert";
        updateTextFromCRDT();
    }

    private void sendDelete() {
        int caretPos = textArea.getCaretPosition();
        if (caretPos == 0 || caretPos > localVisibleChars.size())
            return;

        CRDTCharacter ch = localVisibleChars.remove(caretPos - 1);
        CRDTCharacter deleteChar = new CRDTCharacter('\0', ch.getId(), null, false);
        CRDTMessage msg = new CRDTMessage("delete", deleteChar);
        sendMessage(msg);
        lastOperationType = "delete";
        updateTextFromCRDT();
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
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.send("/app/edit", msg);
        }
    }

    public static void main(String[] args) {
        launch();
    }
}
