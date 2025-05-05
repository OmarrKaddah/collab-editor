// client/controller/PrimaryController.java
package client.controller;

import client.Main;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;

public class PrimaryController {
    @FXML
    private Button newDocButton;
    @FXML
    private Button browseButton;
    @FXML
    private TextField sessionCodeField;
    @FXML
    private Button joinButton;
    private Main mainApp;

    // Add this setter method
    public void setMainApp(Main mainApp) {
        this.mainApp = mainApp;
    }

    @FXML
    private void handleNewDoc() {
        if (mainApp != null) {
            mainApp.handleNewDoc();
        } else {
            System.err.println("Error: Main application reference not set!");
        }
    }

    @FXML
    private void handleBrowse() {
        // Handle file browsing
    }

    @FXML
    private void handleJoin() {
        // Handle joining session
    }
}