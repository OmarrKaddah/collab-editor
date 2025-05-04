package client;

import client.model.CRDTCharacter;
import client.model.CRDTMessage;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
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

    private void applyServerUpdate(CRDTMessage msg) {
        CRDTCharacter ch = msg.getCharacter();
    
        if ("insert".equals(msg.getType())) {
            for (CRDTCharacter existing : localVisibleChars) {
                if (existing.getId().equals(ch.getId())) return; // avoid duplicates
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
    
        updateTextFromCRDT();
    }
    
    


   private void updateTextFromCRDT() {
    StringBuilder sb = new StringBuilder();
    for (CRDTCharacter ch : localVisibleChars) {
        if (ch.isVisible()) {
            sb.append(ch.getValue());
        }
    }
    textArea.setText(sb.toString());
    textArea.positionCaret(localVisibleChars.size()); // ✅ move caret to end
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
            if (ch.isVisible()) visibleCount++;
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
    updateTextFromCRDT();
}




    private void sendDelete() {
        int caretPos = textArea.getCaretPosition();
        if (caretPos == 0 || caretPos > localVisibleChars.size()) return;

        CRDTCharacter ch = localVisibleChars.remove(caretPos - 1);
        CRDTCharacter deleteChar = new CRDTCharacter('\0', ch.getId(), null, false);
        CRDTMessage msg = new CRDTMessage("delete", deleteChar);
        sendMessage(msg);
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
