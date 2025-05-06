package com.example.server.model;

public class CursorUpdate {
    private String username;
    private int position;

    public CursorUpdate(String username, int position) {
        this.username = username;
        this.position = position;
    }

    public String getUsername() {
        return username;
    }

    public int getPosition() {
        return position;
    }
}
