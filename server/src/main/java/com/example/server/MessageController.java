package com.example.server;

import com.example.server.model.CRDTCharacter;
import com.example.server.model.CRDTDocument;
import com.example.server.model.CRDTMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class MessageController {

    private final CRDTDocument crdtDocument;

    @Autowired
    public MessageController() {
        this.crdtDocument = new CRDTDocument();
    }

    @MessageMapping("/edit")
    @SendTo("/topic/updates")
    public CRDTMessage handleEdit(@Payload CRDTMessage incoming) {
        CRDTCharacter character = incoming.getCharacter();

        synchronized (crdtDocument) {
            switch (incoming.getType()) {
                case "insert" -> {
                    // Insert the character as-is (ID & parentId are provided by client)
                    CRDTCharacter inserted = crdtDocument.insert(character);
                    return new CRDTMessage("insert", inserted);
                }
                case "delete" -> {
                    CRDTCharacter deleted = crdtDocument.delete(character.getId());
                    if (deleted != null) {
                        return new CRDTMessage("delete", deleted);
                    } else {
                        return new CRDTMessage("error", null);
                    }
                }
                default -> {
                    System.out.println("Unknown message type: " + incoming.getType());
                    return new CRDTMessage("error", null);
                }
            }
        }
    }
}
