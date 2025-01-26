package test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;

public class App_UI {

    private static JTextArea terminalOutput = new JTextArea(20, 50);  // One JTextArea for terminal output

    public static void main(String[] args) {
        // Create the main window frame
        JFrame frame = new JFrame("Keepa Data Processor");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 500);

        // Create the panel and set its layout
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        // Create checkboxes for selecting locales
        JCheckBox checkBoxIT = new JCheckBox("Process IT Locale");
        JCheckBox checkBoxFR = new JCheckBox("Process FR Locale");
        JCheckBox checkBoxES = new JCheckBox("Process ES Locale");
        JCheckBox checkBoxDE = new JCheckBox("Process DE Locale");

        // Create the submit button
        JButton submitButton = new JButton("Submit");

        // Set the JTextArea to be non-editable for terminal output
        terminalOutput.setEditable(false);

        // Add the checkboxes and button to the panel
        panel.add(checkBoxIT);
        panel.add(checkBoxFR);
        panel.add(checkBoxES);
        panel.add(checkBoxDE);
        panel.add(submitButton);

        // Add the JTextArea wrapped in a JScrollPane
        JScrollPane scrollPane = new JScrollPane(terminalOutput);
        panel.add(scrollPane);

        // Load the terminal output from the file
        loadTerminalOutput();

        // Add action listener to the submit button
        submitButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {

                if (checkBoxIT.isSelected()) {
                    processLocale(() -> App_IT.main(args), "IT");
                }
                if (checkBoxFR.isSelected()) {
                    processLocale(() -> App_FR.main(args), "FR");
                }
                if (checkBoxES.isSelected()) {
                    processLocale(() -> App_ES.main(args), "ES");
                }
                if (checkBoxDE.isSelected()) {
                    processLocale(() -> App_DE.main(args), "DE");
                }
            }
        });

        // Set the frame content pane and make the frame visible
        frame.getContentPane().add(panel);
        frame.setVisible(true);

        // Add a window listener to save the JTextArea content to a file when the application closes
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveTerminalOutput();
            }
        });
    }

    // Method to save the JTextArea content to a file
    private static void saveTerminalOutput() {
        try (PrintWriter out = new PrintWriter(new FileWriter("terminal_output.txt"))) {
            out.print(terminalOutput.getText());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Method to load the JTextArea content from a file
    private static void loadTerminalOutput() {
        try (BufferedReader in = new BufferedReader(new FileReader("terminal_output.txt"))) {
            String line;
            while ((line = in.readLine()) != null) {
                terminalOutput.append(line + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Method to run locale processing and capture the output to JTextArea
    private static void processLocale(Runnable localeTask, String locale) {
        // Create a new thread to handle the locale processing
        new Thread(() -> {
            // Capture the output for this locale
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;

            try {
                // Create a custom output stream that writes to JTextArea
                PrintStream printStream = new PrintStream(new OutputStream() {
                    @Override
                    public void write(int b) {
                        // Update JTextArea in the Swing thread using invokeLater
                        SwingUtilities.invokeLater(() -> {
                            terminalOutput.append(String.valueOf((char) b));
                            terminalOutput.setCaretPosition(terminalOutput.getDocument().getLength());
                        });
                    }
                });

                // Redirect output to JTextArea
                System.setOut(printStream);
                System.setErr(printStream);

                // Run the locale-specific task
                localeTask.run();
            } finally {
                // Restore the original System.out and System.err
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }).start();
    }
}
