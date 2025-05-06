package client.model;

public class CursorUpdate {
    private String username;
    private int position;

    public CursorUpdate() {
    }

    public CursorUpdate(String username, int position) {
        this.username = username;
        this.position = position;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}
