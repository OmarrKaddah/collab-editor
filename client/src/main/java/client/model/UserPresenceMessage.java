package client.model;

public class UserPresenceMessage {
    private String userId;
    private String sessionId;
    private String username;
    private boolean connected;
    private String color;

    // Constructors
    public UserPresenceMessage() {
    }

    public UserPresenceMessage(String userId, String sessionId, String username,
            boolean connected, String color) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.username = username;
        this.connected = connected;
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

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}