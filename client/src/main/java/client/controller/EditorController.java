package client.controller;

import client.Main;
import client.model.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import java.util.HashMap;
import java.util.Map;

public class EditorController {
    @FXML
    private TextArea textArea;
    @FXML
    private TextFlow cursorContainer;
    @FXML
    private VBox userListContainer;
    @FXML
    private Label sessionLabel;
    @FXML
    private Label permissionLabel;

    private Main mainApp;
    private CRDTDocument document;
    private boolean isEditor;
    private String username;
    private String sessionId;
    private Map<String, HBox> userCursors = new HashMap<>();
    private Map<String, Label> userLabels = new HashMap<>();

    public void initialize(Main mainApp, CRDTDocument document, boolean isEditor,
            String username, String sessionId) {
        this.mainApp = mainApp;
        this.document = document;
        this.isEditor = isEditor;
        this.username = username;
        this.sessionId = sessionId;

        setupUI();
        setupEventHandlers();
        updateTextArea();
    }

    private void setupUI() {
        sessionLabel.setText("Session: " + sessionId);
        permissionLabel.setText("Mode: " + (isEditor ? "Editor" : "Viewer"));

        if (!isEditor) {
            textArea.setEditable(false);
        }

        // Initial user list update
        updateUserList();
    }

    private void setupEventHandlers() {
        textArea.addEventHandler(KeyEvent.KEY_TYPED, event -> {
            if (!isEditor)
                return;

            char c = event.getCharacter().charAt(0);
            if (c >= 32 && c <= 126) { // ASCII printable characters
                handleInsert(c);
            }
        });

        textArea.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (!isEditor)
                return;

            switch (event.getCode()) {
                case BACK_SPACE:
                    handleBackspace();
                    break;
                case DELETE:
                    handleDelete();
                    break;
                default:
                    break;
            }
        });
    }

    private void handleInsert(char c) {
        String newId = mainApp.getClientId() + "_" + mainApp.getNextCounter();
        String prevId = document.findCharIdAtPosition(textArea.getCaretPosition() - 1);

        CRDTCharacter ch = new CRDTCharacter(c, newId, prevId, true);
        DocumentUpdateMessage msg = new DocumentUpdateMessage(
                "insert",
                ch,
                mainApp.getClientId(),
                sessionId);

        document.apply(msg);
        mainApp.sendDocumentUpdate(msg);
        updateTextArea();
    }

    private void handleBackspace() {
        int pos = textArea.getCaretPosition();
        if (pos > 0) {
            String charId = document.findCharIdAtPosition(pos - 1);
            if (charId != null) {
                CRDTCharacter ch = new CRDTCharacter('\0', charId, null, false);
                DocumentUpdateMessage msg = new DocumentUpdateMessage(
                        "delete",
                        ch,
                        mainApp.getClientId(),
                        sessionId);

                document.apply(msg);
                mainApp.sendDocumentUpdate(msg);
                updateTextArea();
            }
        }
    }

    private void handleDelete() {
        int pos = textArea.getCaretPosition();
        if (pos < textArea.getText().length()) {
            String charId = document.findCharIdAtPosition(pos);
            if (charId != null) {
                CRDTCharacter ch = new CRDTCharacter('\0', charId, null, false);
                DocumentUpdateMessage msg = new DocumentUpdateMessage(
                        "delete",
                        ch,
                        mainApp.getClientId(),
                        sessionId);

                document.apply(msg);
                mainApp.sendDocumentUpdate(msg);
                updateTextArea();
            }
        }
    }

    public void updateTextArea() {
        Platform.runLater(() -> {
            int caretPos = textArea.getCaretPosition();
            textArea.setText(document.getVisibleText());
            textArea.positionCaret(Math.min(caretPos, textArea.getText().length()));
            updateRemoteCursors();
        });
    }

    public void updateRemoteCursors() {
        Platform.runLater(() -> {
            cursorContainer.getChildren().clear();

            // Add text content
            Text textNode = new Text(textArea.getText());
            cursorContainer.getChildren().add(textNode);

            // Add cursor indicators
            for (CursorPosition cursor : mainApp.getRemoteCursors().values()) {
                if (!cursor.getUserId().equals(mainApp.getClientId())) {
                    int pos = document.findPositionOfChar(cursor.getCharId());
                    if (pos >= 0 && pos <= textArea.getText().length()) {
                        Rectangle cursorIndicator = new Rectangle(2, 20);
                        cursorIndicator.setFill(Color.web(cursor.getColor()));

                        // Position the cursor
                        cursorIndicator.setLayoutX(calculateCursorXPosition(pos));
                        cursorIndicator.setLayoutY(2);

                        // Add username label
                        Text usernameLabel = new Text(cursor.getUsername());
                        usernameLabel.setFill(Color.web(cursor.getColor()));
                        usernameLabel.setLayoutX(calculateCursorXPosition(pos));
                        usernameLabel.setLayoutY(-5);

                        cursorContainer.getChildren().addAll(cursorIndicator, usernameLabel);
                    }
                }
            }
        });
    }

    private double calculateCursorXPosition(int charPosition) {
        // Get the first 500 characters to measure (for performance)
        String text = textArea.getText().substring(0, Math.min(charPosition, 500));
        Text helper = new Text(text);
        helper.setFont(textArea.getFont());

        // Add the helper text to a temporary scene if needed
        if (helper.getScene() == null) {
            new Scene(new Group(helper));
        }
        helper.applyCss();

        // Calculate the width
        return helper.getLayoutBounds().getWidth();
    }

    public void updateUserList() {
        Platform.runLater(() -> {
            userListContainer.getChildren().clear();

            // Add current user first
            addUserToList(mainApp.getClientId(), username, mainApp.getClientColor());

            // Add other users
            for (Map.Entry<String, String> entry : mainApp.getActiveUsers().entrySet()) {
                if (!entry.getKey().equals(mainApp.getClientId())) {
                    addUserToList(entry.getKey(), entry.getValue(),
                            mainApp.getUserColor(entry.getKey()));
                }
            }
        });
    }

    private void addUserToList(String userId, String username, String color) {
        HBox userEntry = new HBox(5);

        Rectangle colorIndicator = new Rectangle(10, 10);
        colorIndicator.setFill(Color.web(color));

        Label nameLabel = new Label(username);
        nameLabel.setStyle("-fx-text-fill: " + color + ";");

        if (userId.equals(mainApp.getClientId())) {
            nameLabel.setText(username + " (You)");
        }

        userEntry.getChildren().addAll(colorIndicator, nameLabel);
        userListContainer.getChildren().add(userEntry);

        userLabels.put(userId, nameLabel);
        userCursors.put(userId, userEntry);
    }

    @FXML
    private void handleExport() {
        // Implementation for exporting document to file
    }

    @FXML
    private void handleLeave() {
        mainApp.handleWindowClose();
        ((Stage) textArea.getScene().getWindow()).close();
    }
}