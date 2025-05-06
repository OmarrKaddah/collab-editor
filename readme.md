Collaborative Text Editor - CRDT-Based

This project is a real-time collaborative text editor implemented using:

- JavaFX for the frontend
- Spring Boot with WebSocket STOMP for the backend
- A custom CRDT (Replicated Growable Array) model for consistency

---

🔧 Requirements:

- Java 17+
- Maven
- JavaFX SDK

---

📦 Project Structure:

- client/ → JavaFX frontend (`Main.java`)
- com.example.server → Spring Boot backend

---

🚀 How to Run:

1. Run the Backend (Spring Boot):

   - From the project root or backend directory, execute:
     ```bash
     mvn spring-boot:run
     ```
   - Backend runs on: `http://localhost:8080/ws`

2. Run the Frontend (JavaFX):

   - From the root directory containing `Main.java`, run:
     ```bash
     mvn javafx:run
     ```

3. Usage:
   - Input your username (max 10 characters)
   - Choose one of:
     - **Create New Document**
     - **Import Text File**
     - **Join Existing Document** using a 5-digit ID with `V` (view) or `E` (edit)

---

💡 Features:

- Real-time updates via WebSocket
- CRDT-based insert/delete consistency
- Undo/Redo and paste support
- Code share via Viewer/Editor ID

---

🧪 Example:

- View code: `12345V`
- Edit code: `12345E`
