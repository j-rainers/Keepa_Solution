package test;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.json.JSONObject;

import com.keepa.api.backend.KeepaAPI;
import com.keepa.api.backend.structs.AmazonLocale;
import com.keepa.api.backend.structs.Request;

import io.github.cdimascio.dotenv.Dotenv;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

public class App_UI extends Application {

    private TextArea terminalOutput;
    private Label tokensLeftLabel;
    private Button calculateButton;
    private Button submitButton;
    private Label calculationResultLabel;
    private TextField apiKeyField;
    private TextField dbUrlField;
    private TextField dbUserField;
    private PasswordField dbPasswordField;
    private TextField dbSchemaField;
    private TextField usernameField;
    private PasswordField passwordField;
    private Button registerButton;
    private Button loginButton;
    private TabPane mainTabPane; // Add this field at the top with other fields
    private boolean isAdminAuthenticated = false;
    private VBox adminContent = null;
    private Map<String, Thread> activeProcesses = new HashMap<>();
    private Map<String, Boolean> processShouldStop = new HashMap<>();
    private Map<String, Process> runningProcesses = new HashMap<>();

    // Token bucket parameters
    private static double TOKEN_LIMIT = 1200; // Will be updated to refillRate * 60
    private static double TOKENS_ADDED_PER_MINUTE = 20; // Will be updated from JSON
    private static final double TOKENS_PER_PRODUCT = 11.4; // Tokens per product request

    private static final String PRIMARY_COLOR = "#2C3E50";
    private static final String ACCENT_COLOR = "#3498DB";
    private static final String BACKGROUND_COLOR = "#ECF0F1";
    private static final String SUCCESS_COLOR = "#2ECC71";
    private static final String WARNING_COLOR = "#F1C40F";

    public static void main(String[] args) {
        // Ensure JavaFX runtime components are initialized
        System.setProperty("javafx.platform", "Desktop");
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        showMainPanel(primaryStage);
    }

    private void showMainPanel(Stage primaryStage) {
        terminalOutput = new TextArea();
        tokensLeftLabel = new Label("Tokens Left: ");
        calculateButton = new Button("Calculate Token Usage");
        submitButton = new Button("Submit");
        calculationResultLabel = new Label("Calculation Result: ");
        apiKeyField = new TextField();
        dbUrlField = new TextField();
        dbUserField = new TextField();
        dbPasswordField = new PasswordField();
        dbSchemaField = new TextField();

        Dotenv dotenv = Dotenv.load();
        String apiKey = dotenv.get("API_KEY");
        KeepaAPI api = new KeepaAPI(apiKey);

        // Call the API and update tokens left before anything else
        requestCategoryLookup(api, AmazonLocale.IT, "IT_category_data.json");
        updateTokensLeftLabel("IT_category_data.json");

        primaryStage.setTitle("Keepa Data Processor");

        mainTabPane = new TabPane(); // Store reference to TabPane
        mainTabPane.setStyle("-fx-background-color: " + BACKGROUND_COLOR + ";");

        // Main Tab
        Tab mainTab = createMainTab();
        Tab adminTab = createAdminTab();

        mainTabPane.getTabs().addAll(mainTab, adminTab);
        
        primaryStage.setMaximized(true); // Make window full-screen
        Scene scene = new Scene(mainTabPane);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        primaryStage.setScene(scene);
        primaryStage.show();

        // Load the terminal output from the file
        loadTerminalOutput();

        // Load existing credentials
        loadCredentials();

        // Add a shutdown hook to save the JTextArea content to a file when the application closes
        primaryStage.setOnCloseRequest(event -> saveTerminalOutput());

        // Start a thread to update tokens left every second
        new Thread(() -> {
            while (true) {
                try {
                    updateTokensLeftFromLatestFile();
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private Tab createMainTab() {
        Tab mainTab = new Tab("Main");
        mainTab.setClosable(false);

        VBox mainContainer = new VBox(20);
        mainContainer.setAlignment(Pos.TOP_CENTER);
        mainContainer.setPadding(new Insets(25));
        mainContainer.setStyle("-fx-background-color: " + BACKGROUND_COLOR + ";");

        // Token Status Panel
        VBox tokenPanel = createTokenStatusPanel();
        tokenPanel.setMaxWidth(800); // Limit maximum width of token panel

        // Locale Selection Panel
        final VBox localePanelContainer = createLocaleSelectionPanel(); // renamed to avoid conflict
        localePanelContainer.setMaxWidth(1200); // Wider to accommodate horizontal layout

        mainContainer.getChildren().addAll(tokenPanel, localePanelContainer);
        
        ScrollPane scrollPane = new ScrollPane(mainContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);

        mainTab.setContent(scrollPane);

        // Add calculate button action
        calculateButton.setOnAction(e -> {
            Map<CheckBox, VBox> localePanels = new HashMap<>();
            HBox localesBox = (HBox) localePanelContainer.getChildren().get(1);
            
            localesBox.getChildren().forEach(container -> {
                if (container instanceof VBox) {
                    VBox localeContainer = (VBox) container;
                    CheckBox checkBox = (CheckBox) localeContainer.getChildren().get(0);
                    VBox inputPanel = (VBox) localeContainer.getChildren().get(1);
                    localePanels.put(checkBox, inputPanel);
                }
            });
            calculateTokenUsage(localePanels);
        });

        return mainTab;
    }

    private VBox createTokenStatusPanel() {
        VBox panel = new VBox(15);
        panel.setStyle("-fx-background-color: white; -fx-padding: 20; -fx-background-radius: 10;");
        
        Label headerLabel = new Label("Token Status");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        
        tokensLeftLabel = new Label("Tokens Left: ");
        tokensLeftLabel.setStyle("-fx-font-size: 14;");
        
        calculationResultLabel = new Label("Calculation Result: ");
        calculationResultLabel.setWrapText(true);
        
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER);
        
        calculateButton = createStyledButton("Calculate Token Usage", ACCENT_COLOR);
        submitButton = createStyledButton("Submit", SUCCESS_COLOR);
        submitButton.setOnAction(e -> {
            ScrollPane scrollPane = (ScrollPane) mainTabPane.getTabs().get(0).getContent();
            VBox mainContainer = (VBox) scrollPane.getContent();
            VBox localePanel = (VBox) mainContainer.getChildren().get(1);
            HBox localesBox = (HBox) localePanel.getChildren().get(1);
            
            boolean hasEmptyFields = false;
            boolean hasSelectedLocales = false;

            for (javafx.scene.Node container : localesBox.getChildren()) {
                if (container instanceof VBox) {
                    VBox localeContainer = (VBox) container;
                    CheckBox checkBox = (CheckBox) localeContainer.getChildren().get(0);
                    if (checkBox.isSelected()) {
                        hasSelectedLocales = true;
                        VBox inputPanel = (VBox) localeContainer.getChildren().get(1);
                        TextField productsField = (TextField) ((VBox) inputPanel).getChildren().get(1);
                        TextField delayField = (TextField) ((VBox) inputPanel).getChildren().get(3);
                        
                        if (productsField.getText().trim().isEmpty() || delayField.getText().trim().isEmpty()) {
                            hasEmptyFields = true;
                            break;
                        }
                    }
                }
            }

            if (hasEmptyFields && hasSelectedLocales) {
                showAlert(AlertType.WARNING, "Validation Error", 
                    "Please enter both product count and delay for all selected locales.");
                return;
            }

            // Proceed with original submit logic
            localesBox.getChildren().forEach(container -> {
                if (container instanceof VBox) {
                    VBox localeContainer = (VBox) container;
                    CheckBox checkBox = (CheckBox) localeContainer.getChildren().get(0);
                    VBox inputPanel = (VBox) localeContainer.getChildren().get(1);
                    
                    if (checkBox.isSelected()) {
                        String locale = checkBox.getText().replace("Process ", "").replace(" Locale", "");
                        terminalOutput.appendText("Processing " + locale + "...\n");
                        
                        switch (locale) {
                            case "IT":
                                processLocale(locale, () -> App_IT.main(new String[0]), inputPanel);
                                break;
                            case "FR":
                                processLocale(locale, () -> App_FR.main(new String[0]), inputPanel);
                                break;
                            case "ES":
                                processLocale(locale, () -> App_ES.main(new String[0]), inputPanel);
                                break;
                            case "DE":
                                processLocale(locale, () -> App_DE.main(new String[0]), inputPanel);
                                break;
                        }
                    }
                }
            });
        });
        
        buttonBox.getChildren().addAll(calculateButton, submitButton);
        
        panel.getChildren().addAll(headerLabel, tokensLeftLabel, calculationResultLabel, buttonBox);
        return panel;
    }

    private VBox createLocaleSelectionPanel() {
        VBox panel = new VBox(15);
        panel.setStyle("-fx-background-color: white; -fx-padding: 20; -fx-background-radius: 10;");
        
        Label headerLabel = new Label("Locale Selection");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        
        // Create horizontal box for locales
        HBox localesBox = new HBox(20);
        localesBox.setAlignment(Pos.CENTER);
        
        Map<CheckBox, VBox> localePanels = new HashMap<>();
        String[] locales = {"IT", "FR", "ES", "DE"};
        
        for (String locale : locales) {
            VBox localeContainer = new VBox(10);
            localeContainer.setAlignment(Pos.TOP_CENTER);
            localeContainer.setPrefWidth(250); // Fixed width for each locale section
            
            CheckBox checkBox = createStyledCheckBox("Process " + locale + " Locale");
            VBox inputPanel = createLocaleInputPanel(locale);
            localePanels.put(checkBox, inputPanel);
            
            checkBox.selectedProperty().addListener((obs, old, newValue) -> {
                inputPanel.setVisible(newValue);
                inputPanel.setManaged(newValue);
            });
            
            localeContainer.getChildren().addAll(checkBox, inputPanel);
            localesBox.getChildren().add(localeContainer);
        }

        panel.getChildren().addAll(headerLabel, localesBox);
        return panel;
    }

    private Button createStyledButton(String text, String backgroundColor) {
        Button button = new Button(text);
        button.setStyle(
            "-fx-background-color: " + backgroundColor + ";" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 14px;" +
            "-fx-padding: 10 20;" +
            "-fx-background-radius: 5;"
        );
        button.setOnMouseEntered(e -> button.setStyle(button.getStyle() + "-fx-opacity: 0.9;"));
        button.setOnMouseExited(e -> button.setStyle(button.getStyle().replace("-fx-opacity: 0.9;", "")));
        return button;
    }

    private VBox createLocaleInputPanel(String localeName) {
        VBox panel = new VBox(10);
        panel.setStyle(
            "-fx-background-color: #f8f9fa;" +
            "-fx-padding: 15;" +
            "-fx-background-radius: 5;" +
            "-fx-border-color: #dee2e6;" +
            "-fx-border-radius: 5;" +
            "-fx-max-width: 230;" + // Adjusted width to fit in horizontal layout
            "-fx-alignment: center;"
        );
        panel.setVisible(false);
        panel.setManaged(false);

        TextField productsField = createStyledTextField("Enter number of products");
        TextField delayField = createStyledTextField("Enter delay in seconds");
        
        // Add status label and stop button
        Label statusLabel = new Label("Status: Ready");
        statusLabel.setStyle("-fx-text-fill: " + PRIMARY_COLOR + ";");
        
        Button stopButton = createStyledButton("Stop", WARNING_COLOR);
        stopButton.setVisible(false);
        stopButton.setOnAction(e -> {
            Thread processThread = activeProcesses.get(localeName);
            if (processThread != null) {
                processThread.interrupt();
                activeProcesses.remove(localeName);
                statusLabel.setText("Status: Stopped");
                statusLabel.setStyle("-fx-text-fill: " + WARNING_COLOR + ";");
                stopButton.setVisible(false);
            }
        });

        panel.getChildren().addAll(
            new Label("Number of Products:"), productsField,
            new Label("Delay (seconds):"), delayField,
            statusLabel,
            stopButton
        );

        // Store references for later access
        panel.getProperties().put("productsField", productsField);
        panel.getProperties().put("delayField", delayField);
        panel.getProperties().put("statusLabel", statusLabel);
        panel.getProperties().put("stopButton", stopButton);

        return panel;
    }

    private void showLoginPage(Stage primaryStage, TabPane tabPane) {
        Stage loginStage = new Stage();
        VBox loginPane = new VBox(10);
        loginPane.setPadding(new Insets(20));
        loginPane.setStyle("-fx-background-color: " + BACKGROUND_COLOR + ";");

        Label titleLabel = new Label("Admin Access");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 16));

        usernameField = createStyledTextField("Username");
        passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        passwordField.setStyle(createStyledTextField("").getStyle());

        loginButton = createStyledButton("Login", ACCENT_COLOR);
        registerButton = createStyledButton("Register", SUCCESS_COLOR);

        // Check if admin credentials exist
        Dotenv dotenv = Dotenv.load();
        String existingUsername = dotenv.get("ADMIN_USERNAME");
        String existingPassword = dotenv.get("ADMIN_PASSWORD");

        if (existingUsername == null || existingPassword == null || 
            existingUsername.trim().isEmpty() || existingPassword.trim().isEmpty()) {
            // No admin exists yet, show only register button
            loginButton.setVisible(false);
            titleLabel.setText("Admin Registration");
        } else {
            // Admin exists, show only login button
            registerButton.setVisible(false);
        }

        loginButton.setOnAction(e -> login(loginStage, mainTabPane)); // Use mainTabPane instead
        registerButton.setOnAction(e -> register(loginStage, mainTabPane)); // Use mainTabPane instead

        loginPane.getChildren().addAll(
            titleLabel,
            new Label("Username:"), usernameField,
            new Label("Password:"), passwordField,
            loginButton, registerButton
        );
        loginPane.setAlignment(Pos.CENTER);
        loginPane.setSpacing(10);

        Scene loginScene = new Scene(loginPane, 350, 400);
        loginStage.setScene(loginScene);
        loginStage.setTitle("Admin Access");
        loginStage.show();
    }

    private void login(Stage loginStage, TabPane tabPane) {
        String username = usernameField.getText();
        String password = passwordField.getText();
        Dotenv dotenv = Dotenv.load();
        String registeredUsername = dotenv.get("ADMIN_USERNAME");
        String registeredPassword = dotenv.get("ADMIN_PASSWORD");

        if (username.equals(registeredUsername) && password.equals(registeredPassword)) {
            isAdminAuthenticated = true;
            loginStage.close();
            showAdminPanel(tabPane);
        } else {
            showAlert(AlertType.ERROR, "Login Error", "Invalid username or password.");
            isAdminAuthenticated = false;
        }
    }

    private void register(Stage loginStage, TabPane tabPane) {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (username.isEmpty() || password.isEmpty()) {
            showAlert(AlertType.ERROR, "Registration Error", "Username and password cannot be empty.");
            return;
        }

        try {
            // Read existing .env content
            Map<String, String> envContents = new HashMap<>();
            if (Files.exists(Paths.get(".env"))) {
                Files.lines(Paths.get(".env")).forEach(line -> {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        envContents.put(parts[0], parts[1]);
                    }
                });
            }

            // Add or update admin credentials
            envContents.put("ADMIN_USERNAME", username);
            envContents.put("ADMIN_PASSWORD", password);

            // Write back to .env file
            try (PrintWriter writer = new PrintWriter(new FileWriter(".env"))) {
                for (Map.Entry<String, String> entry : envContents.entrySet()) {
                    writer.println(entry.getKey() + "=" + entry.getValue());
                }
            }

            showAlert(AlertType.INFORMATION, "Success", "Admin registered successfully!");
            loginStage.close();
            showAdminPanel(tabPane);
        } catch (IOException e) {
            showAlert(AlertType.ERROR, "Error", "Failed to save admin credentials: " + e.getMessage());
        }
    }

    private void showAdminPanel(TabPane tabPane) {
        if (tabPane == null || !isAdminAuthenticated) {
            System.err.println("TabPane is null or not authenticated!");
            return;
        }

        // Create admin panel content if it doesn't exist
        if (adminContent == null) {
            adminContent = new VBox(20);
            adminContent.setPadding(new Insets(20));

            // Create credentials panel
            VBox credentialsPanel = createCredentialsPanel();
            credentialsPanel.setPadding(new Insets(20));

            // Create terminal panel
            VBox terminalPanel = createTerminalPanel();
            
            adminContent.getChildren().addAll(credentialsPanel, terminalPanel);

            // Put the content in a scroll pane
            ScrollPane scrollPane = new ScrollPane(adminContent);
            scrollPane.setFitToWidth(true);
            scrollPane.setFitToHeight(true);

            BorderPane adminPane = new BorderPane();
            adminPane.setStyle("-fx-background-color: " + BACKGROUND_COLOR + ";");
            adminPane.setCenter(scrollPane);

            // Update the admin tab content
            Tab adminTab = tabPane.getTabs().get(1);
            if (adminTab != null) {
                adminTab.setContent(adminPane);
            }

            // Load credentials after setting up the UI
            Platform.runLater(this::loadCredentials);
        }
    }

    private VBox createCredentialsPanel() {
        VBox panel = new VBox(10);
        panel.setStyle("-fx-background-color: white; -fx-padding: 20; -fx-background-radius: 10;");

        Label headerLabel = new Label("API & Database Credentials");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 16));

        apiKeyField = createStyledTextField("API Key");
        dbUrlField = createStyledTextField("Database URL");
        dbUserField = createStyledTextField("Database User");
        dbPasswordField = new PasswordField();
        dbPasswordField.setPromptText("Database Password");
        dbSchemaField = createStyledTextField("Database Schema");

        Button saveCredentialsButton = createStyledButton("Save Credentials", SUCCESS_COLOR);
        saveCredentialsButton.setOnAction(e -> saveCredentials());

        panel.getChildren().addAll(
            headerLabel,
            new Label("API Key:"), apiKeyField,
            new Label("Database URL:"), dbUrlField,
            new Label("Database User:"), dbUserField,
            new Label("Database Password:"), dbPasswordField,
            new Label("Database Schema:"), dbSchemaField,
            saveCredentialsButton
        );

        return panel;
    }

    private VBox createTerminalPanel() {
        VBox panel = new VBox(10);
        panel.setStyle("-fx-background-color: white; -fx-padding: 20; -fx-background-radius: 10;");

        Label headerLabel = new Label("Terminal Output");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 16));

        terminalOutput = new TextArea();
        terminalOutput.setEditable(false);
        terminalOutput.setPrefRowCount(20);
        terminalOutput.setStyle("-fx-font-family: 'Consolas', monospace;");

        // Set the output area in OutputManager
        OutputManager.getInstance().setOutputArea(terminalOutput);

        VBox.setVgrow(terminalOutput, Priority.ALWAYS);
        panel.getChildren().addAll(headerLabel, terminalOutput);

        return panel;
    }

    private void processLocale(String locale, Runnable localeTask, VBox inputPanel) {
        Label statusLabel = (Label) inputPanel.getProperties().get("statusLabel");
        Button stopButton = (Button) inputPanel.getProperties().get("stopButton");
        TextField productsField = (TextField) inputPanel.getProperties().get("productsField");
        TextField delayField = (TextField) inputPanel.getProperties().get("delayField");

        // Reset stop flag for this locale
        processShouldStop.put(locale, false);

        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                try {
                    Platform.runLater(() -> {
                        statusLabel.setText("Status: Processing");
                        statusLabel.setStyle("-fx-text-fill: " + SUCCESS_COLOR + ";");
                        stopButton.setVisible(true);
                    });

                    // Create ProcessBuilder for the locale app
                    ProcessBuilder pb = new ProcessBuilder(
                        "java",
                        "-cp",
                        System.getProperty("java.class.path"),
                        "test.App_" + locale
                    );
                    
                    // Start the process
                    Process process = pb.start();
                    runningProcesses.put(locale, process);
                    
                    // Set up stop button action
                    stopButton.setOnAction(e -> {
                        Process runningProcess = runningProcesses.get(locale);
                        if (runningProcess != null) {
                            // Destroy the process forcefully
                            runningProcess.destroyForcibly();
                            runningProcesses.remove(locale);
                            processShouldStop.put(locale, true);
                            Platform.runLater(() -> {
                                statusLabel.setText("Status: Stopped");
                                statusLabel.setStyle("-fx-text-fill: " + WARNING_COLOR + ";");
                                stopButton.setVisible(false);
                            });
                        }
                    });

                    // Get processing parameters
                    int products = Integer.parseInt(productsField.getText());
                    double delaySeconds = Double.parseDouble(delayField.getText());
                    double processTimeMinutes = (products * delaySeconds) / 60.0;
                    long totalMillis = (long)(processTimeMinutes * 60 * 1000);
                    long startTime = System.currentTimeMillis();

                    // Monitor process
                    while (process.isAlive() && System.currentTimeMillis() - startTime < totalMillis) {
                        if (processShouldStop.get(locale)) {
                            break;  // Exit the monitoring loop if stop was requested
                        }
                        updateProcessStatus(startTime, totalMillis, statusLabel);
                        Thread.sleep(1000);
                    }

                    // Only check completion status if the process wasn't stopped
                    if (!processShouldStop.get(locale)) {
                        // Wait for process completion
                        int exitCode = process.waitFor();
                        
                        Platform.runLater(() -> {
                            if (exitCode == 0) {
                                statusLabel.setText("Status: Completed");
                                statusLabel.setStyle("-fx-text-fill: " + SUCCESS_COLOR + ";");
                            } else {
                                statusLabel.setText("Status: Error (Exit code: " + exitCode + ")");
                                statusLabel.setStyle("-fx-text-fill: #E74C3C;");
                            }
                            stopButton.setVisible(false);
                            runningProcesses.remove(locale);
                        });
                    }

                } catch (Exception e) {
                    if (!processShouldStop.get(locale)) {  // Only show error if not stopped intentionally
                        Platform.runLater(() -> {
                            statusLabel.setText("Status: Error - " + e.getMessage());
                            statusLabel.setStyle("-fx-text-fill: #E74C3C;");
                            stopButton.setVisible(false);
                            runningProcesses.remove(locale);
                        });
                    }
                } finally {
                    processShouldStop.remove(locale);
                }
                return null;
            }
        };

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private void updateProcessStatus(long startTime, long totalMillis, Label statusLabel) {
        double progress = (System.currentTimeMillis() - startTime) / (double) totalMillis;
        long remainingMinutes = (totalMillis - (System.currentTimeMillis() - startTime)) / 60000;
        
        Platform.runLater(() -> {
            statusLabel.setText(String.format("Status: Processing (%.0f%%) - %d min remaining", 
                progress * 100, remainingMinutes));
        });
    }

    private void calculateTokenUsage(Map<CheckBox, VBox> localePanels) {
        boolean hasEmptyFields = false;
        boolean hasSelectedLocales = false;

        for (Map.Entry<CheckBox, VBox> entry : localePanels.entrySet()) {
            CheckBox checkBox = entry.getKey();
            if (checkBox.isSelected()) {
                hasSelectedLocales = true;
                VBox inputPanel = entry.getValue();
                TextField productsField = (TextField) inputPanel.getChildren().get(1);
                TextField delayField = (TextField) inputPanel.getChildren().get(3);
                
                if (productsField.getText().trim().isEmpty() || delayField.getText().trim().isEmpty()) {
                    hasEmptyFields = true;
                    break;
                }
            }
        }

        if (hasEmptyFields && hasSelectedLocales) {
            showAlert(AlertType.WARNING, "Validation Error", 
                "Please enter both product count and delay for all selected locales.");
            return;
        }

        double currentTokens = getCurrentTokens();
        int totalProducts = 0;
        double maxDelaySeconds = 0;
        int selectedLocales = 0;

        for (Map.Entry<CheckBox, VBox> entry : localePanels.entrySet()) {
            CheckBox checkBox = entry.getKey();
            VBox inputPanel = entry.getValue();

            if (checkBox.isSelected()) {
                TextField productsField = (TextField) inputPanel.getProperties().get("productsField");
                TextField delayField = (TextField) inputPanel.getProperties().get("delayField");

                try {
                    int products = Integer.parseInt(productsField.getText());
                    double delaySeconds = Double.parseDouble(delayField.getText());

                    totalProducts += products;
                    maxDelaySeconds = Math.max(maxDelaySeconds, delaySeconds);
                    selectedLocales++;

                } catch (NumberFormatException ex) {
                    terminalOutput.appendText("Invalid input for " + checkBox.getText() + " locale.\n");
                }
            }
        }

        if (totalProducts > 0 && maxDelaySeconds > 0) {
            double processesPerMinute = (60 / maxDelaySeconds) * selectedLocales;
            double tokensUsedPerMinute = processesPerMinute * TOKENS_PER_PRODUCT;
            double netTokenChange = TOKENS_ADDED_PER_MINUTE - tokensUsedPerMinute;

            double totalProcessingTimeMinutes = totalProducts / processesPerMinute;
            double tokensAfterProcessing = currentTokens;

            boolean willTokensDipBelowZero = false;

            for (int i = 0; i < totalProcessingTimeMinutes; i++) {
                tokensAfterProcessing = Math.min(TOKEN_LIMIT, tokensAfterProcessing + TOKENS_ADDED_PER_MINUTE) - tokensUsedPerMinute;
                if (tokensAfterProcessing < 0) {
                    willTokensDipBelowZero = true;
                    break;
                }
            }

            if (willTokensDipBelowZero) {
                calculationResultLabel.setText("Warning: Tokens will dip under zero during processing.");
            } else {
                String result = String.format("Total: Processes/Minute = %.2f, Tokens Used/Minute = %.2f, Net Change = %.2f, Total Processing Time = %.2f minutes",
                        processesPerMinute, tokensUsedPerMinute, netTokenChange, totalProcessingTimeMinutes);
                calculationResultLabel.setText("Calculation Result: " + result);
            }
        }
    }

    private double getCurrentTokens() {
        // Implement logic to get the current token count from the API or a file
        return 3600; // Example value
    }

    private void requestCategoryLookup(KeepaAPI api, AmazonLocale locale, String fileName) {
        Request request = Request.getCategoryLookupRequest(locale, false, 0);
        api.sendRequest(request)
            .done(result -> {
                try (FileWriter writer = new FileWriter(fileName)) {
                    writer.write(result.toString());
                    writer.flush();
                } catch (IOException e) {
                    System.out.println("[" + getCurrentTime() + "] (" + locale + ") " + "Error writing to file: " + e.getMessage());
                }
            })
            .fail(failure -> System.out.println("[" + getCurrentTime() + "] (" + locale + ") " + "Request failed: " + failure));
    }

    private void updateTokensLeftLabel(String fileName) {
        try {
            String content = new String(Files.readAllBytes(Paths.get(fileName)));
            JSONObject json = new JSONObject(content);
            int tokensLeft = json.getInt("tokensLeft");
            
            // Update the token parameters from the JSON
            TOKENS_ADDED_PER_MINUTE = json.getInt("refillRate");
            TOKEN_LIMIT = TOKENS_ADDED_PER_MINUTE * 60;
            
            Platform.runLater(() -> {
                tokensLeftLabel.setText(String.format("Tokens Left: %d (Refill Rate: %.0f/min, Limit: %.0f)", 
                    tokensLeft, TOKENS_ADDED_PER_MINUTE, TOKEN_LIMIT));
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveTerminalOutput() {
        try (PrintWriter out = new PrintWriter(new FileWriter("terminal_output.txt"))) {
            out.print(terminalOutput.getText());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadTerminalOutput() {
        try {
            if (terminalOutput != null) {
                terminalOutput.clear(); // Clear existing content first
                BufferedReader in = new BufferedReader(new FileReader("terminal_output.txt"));
                String line;
                while ((line = in.readLine()) != null) {
                    terminalOutput.appendText(line + "\n");
                }
                in.close();
                // Auto-scroll to the bottom
                terminalOutput.setScrollTop(Double.MAX_VALUE);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getCurrentTime() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        return dtf.format(LocalDateTime.now());
    }

    private void updateTokensLeftFromLatestFile() {
        try (Stream<java.nio.file.Path> paths = Files.list(Paths.get("."))) {
            Optional<java.nio.file.Path> latestFile = paths
                .filter(p -> p.toString().endsWith(".json"))
                .max(Comparator.comparingLong(p -> p.toFile().lastModified()));

            if (latestFile.isPresent()) {
                updateTokensLeftLabel(latestFile.get().toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveCredentials() {
        try {
            // Read existing .env content
            Map<String, String> envContents = new HashMap<>();
            if (Files.exists(Paths.get(".env"))) {
                Files.lines(Paths.get(".env")).forEach(line -> {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        envContents.put(parts[0], parts[1]);
                    }
                });
            }

            // Update with new values while preserving existing ones
            envContents.put("API_KEY", apiKeyField.getText());
            envContents.put("DB_URL", dbUrlField.getText());
            envContents.put("DB_USER", dbUserField.getText());
            envContents.put("DB_PASSWORD", dbPasswordField.getText());
            envContents.put("DB_SCHEMA", dbSchemaField.getText());

            // Write back to .env file
            try (PrintWriter writer = new PrintWriter(new FileWriter(".env"))) {
                for (Map.Entry<String, String> entry : envContents.entrySet()) {
                    writer.println(entry.getKey() + "=" + entry.getValue());
                }
            }

            terminalOutput.appendText("Credentials saved successfully.\n");
        } catch (IOException e) {
            terminalOutput.appendText("Error saving credentials: " + e.getMessage() + "\n");
        }
    }

    private void loadCredentials() {
        Dotenv dotenv = Dotenv.load();
        apiKeyField.setText(dotenv.get("API_KEY"));
        dbUrlField.setText(dotenv.get("DB_URL"));
        dbUserField.setText(dotenv.get("DB_USER"));
        dbPasswordField.setText(dotenv.get("DB_PASSWORD"));
        dbSchemaField.setText(dotenv.get("DB_SCHEMA"));
    }

    private void showAlert(AlertType alertType, String title, String message) {
        Alert alert = new Alert(alertType);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private Tab createAdminTab() {
        Tab adminTab = new Tab("Admin");
        adminTab.setClosable(false);

        // Create initial empty pane
        VBox emptyPane = new VBox();
        emptyPane.setStyle("-fx-background-color: " + BACKGROUND_COLOR + ";");
        adminTab.setContent(emptyPane);

        // Show login page when tab is selected and not authenticated
        adminTab.setOnSelectionChanged(event -> {
            if (adminTab.isSelected()) {
                if (!isAdminAuthenticated) {
                    showLoginPage(null, mainTabPane);
                    // If not authenticated, switch back to main tab
                    if (!isAdminAuthenticated) {
                        mainTabPane.getSelectionModel().select(0);
                    }
                } else {
                    // Load terminal content when admin tab is opened
                    loadTerminalOutput();
                }
            }
        });

        return adminTab;
    }

    private CheckBox createStyledCheckBox(String text) {
        CheckBox checkBox = new CheckBox(text);
        checkBox.setStyle(
            "-fx-text-fill: " + PRIMARY_COLOR + ";" +
            "-fx-font-size: 14px;" +
            "-fx-padding: 5 10;"
        );
        
        // Add tooltip with instructions
        checkBox.setTooltip(new javafx.scene.control.Tooltip(
            "Select to process " + text.replace("Process ", "").replace(" Locale", "")
        ));
        
        return checkBox;
    }

    private TextField createStyledTextField(String promptText) {
        TextField textField = new TextField();
        textField.setPromptText(promptText);
        textField.setStyle(
            "-fx-background-color: white;" +
            "-fx-border-color: #ced4da;" +
            "-fx-border-radius: 3;" +
            "-fx-padding: 8;" +
            "-fx-pref-width: 200;"  // Added fixed width for consistency
        );
        return textField;
    }
}