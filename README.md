# Collaborative Plain Text Editor

A real-time collaborative plain text editor built using **JavaFX** (frontend) and **Spring Boot** (backend). This editor enables multiple users to simultaneously edit the same document through character-based CRDT synchronization and WebSocket-based communication.

---

## Features

### Document & Collaboration Management
- Import/Export `.txt` files (with preserved line breaks)
- Generate shareable codes for:
  - **Editor access** (full control)
  - **Viewer access** (read-only)
- Join collaborative sessions using these codes

### Real-time Collaborative Editing
- Character-by-character insertion and deletion
- Pasted text treated as a sequence of insertions
- Real-time updates across all connected users
- Tree-based **CRDT** algorithm for conflict-free edits
- Cursor tracking of all collaborators
- Display of currently active users
- Undo/Redo support (up to 3 local operations)
- Reconnection within 5 minutes after network drop

### UI Design
- Built with **JavaFX**
- Text area with live updates
- Menu for import/export and sharing
- Permission-based behavior (editor vs viewer)
- Visual cursor indicators (color-coded, max 4 users)
- User presence list

---

## Technologies Used

- Java 17+
- JavaFX (UI)
- Spring Boot (REST + WebSocket server)
- STOMP over WebSocket (messaging)
- Gson (JSON parsing)

---


## Getting Started

### 1. Prerequisites

- Java 17 or later
- Maven 3.6 or later
- Git

---

## Running the Project

### Step 1: Clone the Repository

```bash
git clone https://github.com/your-username/collaborative-editor.git
cd collaborative-editor
```

---

### Step 2: Run the Server

```bash
cd server
mvn spring-boot:run
```

- Server starts at `http://localhost:8080`
- WebSocket endpoint available at `ws://localhost:8080/ws`

---

### Step 3: Run the Client

```bash
cd ../client
mvn javafx:run
```

- The JavaFX GUI will launch.
- You can now create or join sessions and collaborate in real time.

---

## Reconnection Logic

- If the user loses connection:
  - They are placed in a reconnecting list for 5 minutes.
  - Missed remote edits are queued and sent upon reconnect.
  - Local edits are also synced post-reconnection.
  - If reconnect fails after timeout, session access is revoked.

---

## Limitations

- Viewers cannot edit or access shareable editor codes.
- Only ASCII characters are supported.
- Maximum of 4 concurrent editors recommended (due to color distinction).
- Undo/Redo only tracks local operations (not global).

---

## Bonus Features (Optional)

- Text comment system: Users can annotate parts of the text.
- Reconnection support: Offline edits and remote sync after reconnect.

---
