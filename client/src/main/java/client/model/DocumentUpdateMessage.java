package client.model;

public class DocumentUpdateMessage {
    private String type; // "insert" or "delete"
    private CRDTCharacter character;
    private String userId;
    private String sessionId;

    // Constructors
    public DocumentUpdateMessage() {
    }

    public DocumentUpdateMessage(String type, CRDTCharacter character,
            String userId, String sessionId) {
        this.type = type;
        this.character = character;
        this.userId = userId;
        this.sessionId = sessionId;
    }

    // Getters and Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public CRDTCharacter getCharacter() {
        return character;
    }

    public void setCharacter(CRDTCharacter character) {
        this.character = character;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}