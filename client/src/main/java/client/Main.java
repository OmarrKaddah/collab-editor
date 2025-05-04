package client;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import client.controller.EditorController;
import client.model.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

public class Main extends Application {

    // UI Components
    @FXML
    private Button newDocButton;
    @FXML
    private Button browseButton;
    @FXML
    private TextField sessionCodeField;
    @FXML
    private Button joinButton;
    @FXML
    private ListView<String> activeSessionsListView;
    @FXML
    private VBox homeContainer;
    @FXML
    private HBox sessionCodeContainer;
    @FXML
    private Button copyEditorCodeButton;
    @FXML
    private Button copyViewerCodeButton;
    @FXML
    private Label editorCodeLabel;
    @FXML
    private Label viewerCodeLabel;

    // Application state
    private StompSession stompSession;
    private CRDTDocument document = new CRDTDocument();
    private String clientId = UUID.randomUUID().toString().substring(0, 6);
    private final AtomicInteger localCounter = new AtomicInteger(0);
    private String currentSessionId;
    private boolean isEditor = false;
    private String username = "User_" + (int) (Math.random() * 1000);
    private Map<String, CursorPosition> remoteCursors = new ConcurrentHashMap<>();
    private Map<String, String> activeUsers = new ConcurrentHashMap<>();
    private Timer cursorUpdateTimer;

    // Constants
    private static final String SERVER_WS_URL = "ws://localhost:8080/ws";
    private static final long CURSOR_UPDATE_INTERVAL = 200; // ms

    @Override
    public void start(Stage stage) {
        System.out.println("Starting application...");

        try {
            // Load main UI
            URL fxmlUrl = getClass().getResource("/client/primary.fxml");
            if (fxmlUrl == null) {
                throw new RuntimeException("FXML file not found");
            }

            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            loader.setController(this);
            Parent root = loader.load();

            // Setup UI
            Scene scene = new Scene(root);
            URL cssUrl = getClass().getResource("/client/styles.css");
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }

            stage.setScene(scene);
            stage.setTitle("Collaborative Text Editor");
            stage.show();

            // Connect to server
            connectToServer();

        } catch (Exception e) {
            e.printStackTrace();
            showError("Initialization Error", "Failed to start application: " + e.getMessage());
        }
    }

    private void connectToServer() {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        stompClient.connect(SERVER_WS_URL, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                stompSession = session;
                System.out.println("Connected to WebSocket server");

                // Subscribe to document updates
                session.subscribe("/topic/document/" + currentSessionId, new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return DocumentUpdateMessage.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        DocumentUpdateMessage msg = (DocumentUpdateMessage) payload;
                        handleDocumentUpdate(msg);
                    }
                });

                // Subscribe to cursor updates
                session.subscribe("/topic/cursors/" + currentSessionId, new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return CursorUpdateMessage.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        CursorUpdateMessage msg = (CursorUpdateMessage) payload;
                        handleCursorUpdate(msg);
                    }
                });

                // Subscribe to user presence updates
                session.subscribe("/topic/users/" + currentSessionId, new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return UserPresenceMessage.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        UserPresenceMessage msg = (UserPresenceMessage) payload;
                        handleUserPresenceUpdate(msg);
                    }
                });

                // Start cursor position updates
                startCursorUpdates();

                // Notify server of our presence
                sendUserPresenceUpdate(true);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                System.err.println("Transport error: " + exception.getMessage());
                attemptReconnect();
            }

            @Override
            public void handleException(StompSession session, StompCommand command,
                    StompHeaders headers, byte[] payload, Throwable exception) {
                System.err.println("Handler exception: " + exception.getMessage());
            }
        });
    }

    private void attemptReconnect() {
        System.out.println("Attempting to reconnect...");
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> connectToServer());
            }
        }, 5000); // Try again after 5 seconds
    }

    @FXML
    private void handleNewDoc() {
        currentSessionId = generateSessionCode();
        isEditor = true;
        username = "User_" + (int) (Math.random() * 1000);

        // Generate session codes
        String editorCode = "#" + currentSessionId + "E";
        String viewerCode = "#" + currentSessionId + "V";

        // Show codes in UI
        editorCodeLabel.setText(editorCode);
        viewerCodeLabel.setText(viewerCode);
        sessionCodeContainer.setVisible(true);

        // Initialize empty document
        document = new CRDTDocument();

        // Navigate to editor
        navigateToEditor();
    }

    @FXML
    private void handleBrowse() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Text File");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));

        File file = fileChooser.showOpenDialog(homeContainer.getScene().getWindow());

        if (file != null) {
            try {
                String content = Files.readString(file.toPath());
                currentSessionId = generateSessionCode();
                isEditor = true;
                username = "User_" + (int) (Math.random() * 1000);

                // Generate session codes
                String editorCode = "#" + currentSessionId + "E";
                String viewerCode = "#" + currentSessionId + "V";

                // Show codes in UI
                editorCodeLabel.setText(editorCode);
                viewerCodeLabel.setText(viewerCode);
                sessionCodeContainer.setVisible(true);

                // Initialize document with file content
                document = new CRDTDocument();
                for (char c : content.toCharArray()) {
                    String newId = clientId + "_" + localCounter.getAndIncrement();
                    String prevId = document.findLastVisibleCharId();
                    document.apply(new DocumentUpdateMessage(
                            "insert",
                            new CRDTCharacter(c, newId, prevId, true),
                            clientId,
                            currentSessionId));
                }

                // Navigate to editor
                navigateToEditor();

            } catch (IOException e) {
                showError("File Error", "Could not read the file: " + e.getMessage());
            }
        }
    }

    @FXML
    private void handleJoin() {
        String code = sessionCodeField.getText().trim();
        if (code.isEmpty()) {
            showError("Validation Error", "Please enter a session code");
            return;
        }

        // Parse code (format: #ABC123E or #ABC123V)
        if (!code.startsWith("#") || code.length() < 2) {
            showError("Invalid Code", "Session code must start with #");
            return;
        }

        char permission = code.charAt(code.length() - 1);
        if (permission != 'E' && permission != 'V') {
            showError("Invalid Code", "Session code must end with E (editor) or V (viewer)");
            return;
        }

        currentSessionId = code.substring(1, code.length() - 1);
        isEditor = (permission == 'E');
        username = "User_" + (int) (Math.random() * 1000);

        // Navigate to editor (document will be loaded via WebSocket)
        navigateToEditor();
    }

    @FXML
    private void copyEditorCode() {
        copyToClipboard(editorCodeLabel.getText());
    }

    @FXML
    private void copyViewerCode() {
        copyToClipboard(viewerCodeLabel.getText());
    }

    private void copyToClipboard(String text) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Copied");
        alert.setHeaderText(null);
        alert.setContentText("Code copied to clipboard!");
        alert.showAndWait();
    }

    private void navigateToEditor() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/editor.fxml"));
            Parent root = loader.load();

            EditorController controller = loader.getController();
            controller.initialize(this, document, isEditor, username, currentSessionId);

            Stage stage = new Stage();
            stage.setScene(new Scene(root));
            stage.setTitle(
                    "Collaborative Editor - " + currentSessionId + " (" + (isEditor ? "Editor" : "Viewer") + ")");
            stage.setOnCloseRequest(e -> handleWindowClose());
            stage.show();

            // Close home window
            ((Stage) homeContainer.getScene().getWindow()).close();

        } catch (IOException e) {
            showError("Navigation Error", "Could not open editor: " + e.getMessage());
        }
    }

    private void handleDocumentUpdate(DocumentUpdateMessage msg) {
        if (!msg.getSessionId().equals(currentSessionId))
            return;

        Platform.runLater(() -> {
            // Apply update to local document
            document.apply(msg);

            // Update UI
            if (msg.getType().equals("insert") || msg.getType().equals("delete")) {
                // Only update text if it's a content change
                updateTextArea();
            }
        });
    }

    private void handleCursorUpdate(CursorUpdateMessage msg) {
        if (!msg.getSessionId().equals(currentSessionId))
            return;

        Platform.runLater(() -> {
            // Store or update cursor position
            remoteCursors.put(msg.getUserId(), new CursorPosition(
                    msg.getUserId(),
                    String.valueOf(msg.getPosition()),
                    msg.getUsername(),
                    msg.getColor()));

            // Update cursor visuals in UI
            updateRemoteCursors();
        });
    }

    private void handleUserPresenceUpdate(UserPresenceMessage msg) {
        Platform.runLater(() -> {
            if (msg.isConnected()) {
                activeUsers.put(msg.getUserId(), msg.getUsername());
            } else {
                activeUsers.remove(msg.getUserId());
                remoteCursors.remove(msg.getUserId());
            }

            // Update user list in UI
            updateActiveUsersList();
            updateRemoteCursors();
        });
    }

    private void startCursorUpdates() {
        cursorUpdateTimer = new Timer(true);
        cursorUpdateTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (stompSession != null && stompSession.isConnected()) {
                    // Get current cursor position from editor
                    int caretPosition = getCurrentCaretPosition();
                    String charId = document.findCharIdAtPosition(caretPosition);

                    // Send update to server
                    CursorUpdateMessage msg = new CursorUpdateMessage(
                            clientId,
                            currentSessionId,
                            username,
                            charId,
                            caretPosition,
                            getClientColor());

                    stompSession.send("/app/cursor", msg);
                }
            }
        }, 0, CURSOR_UPDATE_INTERVAL);
    }

    private void sendUserPresenceUpdate(boolean connected) {
        if (stompSession != null && stompSession.isConnected()) {
            UserPresenceMessage msg = new UserPresenceMessage(
                    clientId,
                    currentSessionId,
                    username,
                    connected,
                    getClientColor());
            stompSession.send("/app/user", msg);
        }
    }

    public void sendDocumentUpdate(DocumentUpdateMessage msg) {
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.send("/app/document", msg);
        }
    }

    private void updateTextArea() {
        // This would be called by the EditorController to update its text area
    }

    private void updateRemoteCursors() {
        // This would be handled by the EditorController to draw remote cursors
    }

    private void updateActiveUsersList() {
        // This would update the user list in the EditorController
    }

    private int getCurrentCaretPosition() {
        // This would be implemented in EditorController
        return 0;
    }

    public String getClientColor() {
        // Generate a consistent color based on client ID
        int hash = clientId.hashCode();
        return String.format("#%06X", (hash & 0xFFFFFF));
    }

    public void handleWindowClose() {
        // Clean up resources
        if (cursorUpdateTimer != null) {
            cursorUpdateTimer.cancel();
        }

        // Notify server we're leaving
        sendUserPresenceUpdate(false);

        // Close WebSocket connection
        if (stompSession != null) {
            stompSession.disconnect();
        }
    }

    private String generateSessionCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        return sb.toString();
    }

    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    // In Main.java
    public Map<String, CursorPosition> getRemoteCursors() {
        return remoteCursors;
    }

    public Map<String, String> getActiveUsers() {
        return activeUsers;
    }

    public String getUserColor(String userId) {
        return getColorForUser(userId);
    }

    private String getColorForUser(String userId) {
        // Simple hash-based color generation
        int hash = userId.hashCode();
        return String.format("#%06X", (hash & 0x00FFFFFF));
    }

    // Add these to your existing Main class
    public String getClientId() {
        return this.clientId;
    }

    public int getNextCounter() {
        return this.localCounter.getAndIncrement();
    }

    public static void main(String[] args) {
        launch(args);
    }
}