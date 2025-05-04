package client.model;



public class CRDTCharacter {
    private char value;
    private String id;        // Unique: userID + counter or UUID
    private String parentId;    // ID of the character this was inserted after
    private boolean visible;  // false if deleted

    public CRDTCharacter() {}

    public CRDTCharacter(char value, String id, String prevId, boolean visible) {
        this.value = value;
        this.id = id;
        this.parentId = prevId;
        this.visible = visible;
    }

    public char getValue() { return value; }
    public String getId() { return id; }
    public String getParentId() { return parentId; }
    public boolean isVisible() { return visible; }

    public void setValue(char value) { this.value = value; }
    public void setId(String id) { this.id = id; }
    public void setParentId(String prevId) { this.parentId = prevId; }
    public void setVisible(boolean visible) { this.visible = visible; }
}
