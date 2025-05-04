package com.example.server.model;

public class CRDTMessage {
    private String type; // "insert" or "delete"
    private CRDTCharacter character;

    public CRDTMessage() {}

    public CRDTMessage(String type, CRDTCharacter character) {
        this.type = type;
        this.character = character;
    }

    public String getType() { return type; }
    public CRDTCharacter getCharacter() { return character; }

    public void setType(String type) { this.type = type; }
    public void setCharacter(CRDTCharacter character) { this.character = character; }
}
