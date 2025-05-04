package client.model;

public class CursorUpdateMessage {
    private String userId;
    private String sessionId;
    private String username;
    private String charId;
    private int position;
    private String color;

    // Constructors
    public CursorUpdateMessage() {
    }

    public CursorUpdateMessage(String userId, String sessionId, String username,
            String charId, int position, String color) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.username = username;
        this.charId = charId;
        this.position = position;
        this.color = color;
    }

    // Getters and Setters
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getCharId() {
        return charId;
    }

    public void setCharId(String charId) {
        this.charId = charId;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}