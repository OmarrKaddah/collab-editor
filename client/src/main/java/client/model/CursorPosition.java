package client.model;

import javafx.scene.paint.Color;

public class CursorPosition {
    private final String userId;
    private final String charId;
    private final int position;
    private final String username;
    private final String color;

    public CursorPosition(String userId, String charId, String username, String color) {
        this.userId = userId;
        this.charId = charId;
        this.username = username;
        this.color = color;
        this.position = 0; // Will be calculated based on document state
    }

    // Getters
    public String getUserId() {
        return userId;
    }

    public String getCharId() {
        return charId;
    }

    public int getPosition() {
        return position;
    }

    public String getUsername() {
        return username;
    }

    public String getColor() {
        return color;
    }
}