package com.example.server;

import com.example.server.model.CRDTCharacter;
import com.example.server.model.CRDTDocument;
import com.example.server.model.CRDTMessage;

import com.example.server.service.DocumentService;
import com.example.server.service.UserSessionService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class MessageController {

    private final DocumentService documentService;

    private final SimpMessagingTemplate messagingTemplate;

    private final UserSessionService userSessionService;

    @Autowired
    public MessageController(SimpMessagingTemplate messagingTemplate,
            DocumentService documentService,
            UserSessionService userSessionService) {

        this.messagingTemplate = messagingTemplate;
        this.documentService = documentService;
        this.userSessionService = userSessionService;
    }

    @MessageMapping("/edit/{docId}")
    @SendTo("/topic/updates/{docId}")
    public CRDTMessage handleEdit(
            @Payload CRDTMessage incoming,
            @DestinationVariable String docId) {

        CRDTDocument document = documentService.getDocument(docId);

        CRDTCharacter character = incoming.getCharacter();

        synchronized (document) {
            switch (incoming.getType()) {
                case "insert" -> {
                    System.out.printf("[%s] Insert: %s after %s%n",
                            docId, character.getValue(), character.getParentId());
                    CRDTCharacter inserted = document.insert(character);
                    return new CRDTMessage("insert", inserted);
                }
                case "delete" -> {
                    System.out.printf("[%s] Delete: %s%n", docId, character.getId());
                    CRDTCharacter deleted = document.delete(character.getId());
                    if (deleted != null) {
                        return new CRDTMessage("delete", deleted);
                    }
                    return new CRDTMessage("error", null);
                }
                default -> {
                    System.out.printf("[%s] Unknown message type: %s%n",
                            docId, incoming.getType());
                    return new CRDTMessage("error", null);
                }
            }
        }
    }

    @MessageMapping("/sync/{docId}")
    @SendToUser("/queue/sync")
    public List<CRDTMessage> handleSyncRequest(@DestinationVariable String docId) {
        CRDTDocument document = documentService.getDocument(docId);
        if (document == null) {
            System.out.printf("[%s] Sync requested but document not found%n", docId);
            return List.of();
        }

        System.out.printf("[%s] Sending full sync to requesting user%n", docId);

        return document.getAllCharacters().stream()
                .map(c -> new CRDTMessage("insert", c))
                .toList();
    }

    @MessageMapping("/create/{docId}")
    public void handleCreate(@DestinationVariable String docId) {
        documentService.getDocument(docId);
        System.out.println("✅ Created new document with ID: " + docId);
    }

    @MessageMapping("/exists/{docId}")
    @SendToUser("/queue/exists")
    public boolean handleExistCheck(@DestinationVariable String docId) {
        System.out.println("Checking existence of document: " + docId);
        boolean x = documentService.exists(docId);
        System.out.println("Document " + (x ? "exists" : "does not exist") + ": " + docId);
        return x;
    }

    @MessageMapping("/join/{docId}")
    public void handleJoin(@DestinationVariable String docId, @Payload String username) {
        userSessionService.addUser(docId, username);
        broadcastUserList(docId);
    }

    @MessageMapping("/leave/{docId}")
    public void handleLeave(@DestinationVariable String docId, @Payload String username) {
        userSessionService.removeUser(docId, username);

        broadcastUserList(docId);

    }

    private void broadcastUserList(String docId) {
        String[] users = userSessionService.getUsers(docId);
        messagingTemplate.convertAndSend("/topic/users/" + docId, users);
    }

}