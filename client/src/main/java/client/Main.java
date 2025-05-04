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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class Main extends Application {

    private StompSession stompSession;
    private TextArea textArea;

    private final String clientId = UUID.randomUUID().toString().substring(0, 6);
    private final AtomicLong lastTimestamp = new AtomicLong(0);

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

        // Handle key input
        textArea.setOnKeyTyped(event -> {
            String typed = event.getCharacter();
            event.consume(); // Block direct insertion

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
        if ("insert".equals(msg.getType())) {
            textArea.appendText(String.valueOf(msg.getCharacter().getValue()));
        } else if ("delete".equals(msg.getType())) {
            String current = textArea.getText();
            if (!current.isEmpty()) {
                textArea.setText(current.substring(0, current.length() - 1));
                textArea.positionCaret(textArea.getText().length());
            }
        }
    }

    private void sendInsert(char c) {
        String id = generateUniqueId();
        String prevId = "DUMMY"; // placeholder, server handles real CRDT logic

        CRDTCharacter ch = new CRDTCharacter(c, id, prevId, true);
        CRDTMessage msg = new CRDTMessage("insert", ch);
        sendMessage(msg);
    }

    private void sendDelete() {
        String lastCharId = "UNKNOWN"; // client doesn’t track character tree
        CRDTCharacter ch = new CRDTCharacter('\0', lastCharId, null, false);
        CRDTMessage msg = new CRDTMessage("delete", ch);
        sendMessage(msg);
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
