package test;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import javafx.application.Platform;
import javafx.scene.control.TextArea;

public class OutputManager {
    private static OutputManager instance;
    private TextArea outputArea;
    private StringBuilder buffer = new StringBuilder();

    private OutputManager() {}

    public static OutputManager getInstance() {
        if (instance == null) {
            instance = new OutputManager();
        }
        return instance;
    }

    public void setOutputArea(TextArea outputArea) {
        this.outputArea = outputArea;
    }

    public void println(String message) {
        // Update UI
        if (outputArea != null) {
            Platform.runLater(() -> {
                outputArea.appendText(message + "\n");
                outputArea.setScrollTop(Double.MAX_VALUE);
            });
        }
        
        // Store in buffer
        buffer.append(message).append("\n");
        
        // Write to file
        try (PrintWriter out = new PrintWriter(new FileWriter("terminal_output.txt", true))) {
            out.println(message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getBuffer() {
        return buffer.toString();
    }

    public void clear() {
        buffer.setLength(0);
        if (outputArea != null) {
            Platform.runLater(() -> outputArea.clear());
        }
    }
}
