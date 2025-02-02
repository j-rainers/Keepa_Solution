package test;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
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
import javafx.scene.layout.VBox;
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

    // Token bucket parameters
    private static final double TOKEN_LIMIT = 3600; // Maximum tokens in the bucket
    private static final double TOKENS_ADDED_PER_MINUTE = 60; // Tokens refill rate
    private static final double TOKENS_PER_PRODUCT = 11.4; // Tokens per product request

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

        TabPane tabPane = new TabPane();

        // Main Tab
        Tab mainTab = new Tab("Main");
        mainTab.setClosable(false);
        BorderPane mainPane = new BorderPane();

        VBox leftPanel = new VBox(10);
        leftPanel.setPadding(new Insets(10));
        leftPanel.setStyle("-fx-background-color: #302648;");

        CheckBox checkBoxIT = new CheckBox("Process IT Locale");
        CheckBox checkBoxFR = new CheckBox("Process FR Locale");
        CheckBox checkBoxES = new CheckBox("Process ES Locale");
        CheckBox checkBoxDE = new CheckBox("Process DE Locale");

        Map<CheckBox, VBox> localePanels = new HashMap<>();

        // Add dynamic input fields for each locale
        localePanels.put(checkBoxIT, createLocaleInputPanel("IT"));
        localePanels.put(checkBoxFR, createLocaleInputPanel("FR"));
        localePanels.put(checkBoxES, createLocaleInputPanel("ES"));
        localePanels.put(checkBoxDE, createLocaleInputPanel("DE"));

        for (Map.Entry<CheckBox, VBox> entry : localePanels.entrySet()) {
            CheckBox checkBox = entry.getKey();
            VBox inputPanel = entry.getValue();
            inputPanel.setVisible(false); // Initially hidden
            checkBox.selectedProperty().addListener((observable, oldValue, newValue) -> inputPanel.setVisible(newValue));
            leftPanel.getChildren().addAll(checkBox, inputPanel);
        }

        calculateButton.setOnAction(e -> calculateTokenUsage(localePanels));
        submitButton.setOnAction(e -> {
            for (Map.Entry<CheckBox, VBox> entry : localePanels.entrySet()) {
                CheckBox checkBox = entry.getKey();
                if (checkBox.isSelected()) {
                    String locale = checkBox.getText();
                    terminalOutput.appendText("Processing " + locale + "...\n");
                    switch (locale) {
                        case "Process IT Locale":
                            processLocale(() -> App_IT.main(new String[0]));
                            break;
                        case "Process FR Locale":
                            processLocale(() -> App_FR.main(new String[0]));
                            break;
                        case "Process ES Locale":
                            processLocale(() -> App_ES.main(new String[0]));
                            break;
                        case "Process DE Locale":
                            processLocale(() -> App_DE.main(new String[0]));
                            break;
                    }
                }
            }
        });

        leftPanel.getChildren().addAll(tokensLeftLabel, calculateButton, submitButton, calculationResultLabel);
        mainPane.setLeft(leftPanel);

        mainTab.setContent(mainPane);
        tabPane.getTabs().add(mainTab);

        // Admin Tab
        Tab adminTab = new Tab("Admin");
        adminTab.setClosable(false);
        adminTab.setOnSelectionChanged(event -> {
            if (adminTab.isSelected()) {
                showLoginPage(primaryStage, tabPane);
            }
        });

        tabPane.getTabs().add(adminTab);

        Scene scene = new Scene(tabPane, 800, 600);
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

    private void showLoginPage(Stage primaryStage, TabPane tabPane) {
        Stage loginStage = new Stage();
        VBox loginPane = new VBox(10);
        loginPane.setPadding(new Insets(10));
        loginPane.setStyle("-fx-background-color: #302648;");

        usernameField = new TextField();
        passwordField = new PasswordField();
        loginButton = new Button("Login");

        loginPane.getChildren().addAll(
            new Label("Username:"), usernameField,
            new Label("Password:"), passwordField,
            loginButton
        );

        loginButton.setOnAction(e -> login(loginStage, tabPane));

        Scene loginScene = new Scene(loginPane, 300, 200);
        loginStage.setScene(loginScene);
        loginStage.setTitle("Admin Login");
        loginStage.show();
    }

    private void login(Stage loginStage, TabPane tabPane) {
        String username = usernameField.getText();
        String password = passwordField.getText();
        Dotenv dotenv = Dotenv.load();
        String registeredUsername = dotenv.get("ADMIN_USERNAME");
        String registeredPassword = dotenv.get("ADMIN_PASSWORD");

        if (username.equals(registeredUsername) && password.equals(registeredPassword)) {
            loginStage.close();
            showAdminPanel(tabPane);
        } else {
            showAlert(AlertType.ERROR, "Login Error", "Invalid username or password.");
        }
    }

    private void showAdminPanel(TabPane tabPane) {
        terminalOutput = new TextArea();
        apiKeyField = new TextField();
        dbUrlField = new TextField();
        dbUserField = new TextField();
        dbPasswordField = new PasswordField();
        dbSchemaField = new TextField();

        Dotenv dotenv = Dotenv.load();
        String apiKey = dotenv.get("API_KEY");
        KeepaAPI api = new KeepaAPI(apiKey);

        BorderPane adminPane = new BorderPane();
        adminPane.setStyle("-fx-background-color: #302648;");

        terminalOutput.setEditable(false);
        ScrollPane scrollPane = new ScrollPane(terminalOutput);
        adminPane.setCenter(scrollPane);

        VBox credentialsPanel = new VBox(10);
        credentialsPanel.setPadding(new Insets(10));
        credentialsPanel.setStyle("-fx-background-color: #302648;");

        credentialsPanel.getChildren().addAll(
                new Label("API Key:"), apiKeyField,
                new Label("Database URL:"), dbUrlField,
                new Label("Database User:"), dbUserField,
                new Label("Database Password:"), dbPasswordField,
                new Label("Database Schema:"), dbSchemaField
        );

        Button saveCredentialsButton = new Button("Save Credentials");
        saveCredentialsButton.setOnAction(e -> saveCredentials());

        credentialsPanel.getChildren().add(saveCredentialsButton);
        adminPane.setTop(credentialsPanel);

        Tab adminTab = new Tab("Admin");
        adminTab.setClosable(false);
        adminTab.setContent(adminPane);

        tabPane.getTabs().set(1, adminTab);
    }

    private VBox createLocaleInputPanel(String localeName) {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: #302648;");
        panel.getChildren().add(new Label("Settings for " + localeName + " Locale:"));

        TextField productsField = new TextField();
        TextField delayField = new TextField();

        panel.getChildren().addAll(
                new Label("Number of Products:"), productsField,
                new Label("Delay Between Processes (seconds):"), delayField
        );

        panel.getProperties().put("productsField", productsField);
        panel.getProperties().put("delayField", delayField);

        return panel;
    }

    private void calculateTokenUsage(Map<CheckBox, VBox> localePanels) {
        double currentTokens = getCurrentTokens(); // Assume this method gets the current token count

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
            Platform.runLater(() -> tokensLeftLabel.setText("Tokens Left: " + tokensLeft));
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
        try (BufferedReader in = new BufferedReader(new FileReader("terminal_output.txt"))) {
            String line;
            while ((line = in.readLine()) != null) {
                terminalOutput.appendText(line + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void processLocale(Runnable localeTask) {
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() {
                PrintStream originalOut = System.out;
                PrintStream originalErr = System.err;

                try (PrintStream printStream = new PrintStream(new FileOutputStream("terminal_output.txt", true))) {
                    System.setOut(printStream);
                    System.setErr(printStream);

                    localeTask.run();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    System.setOut(originalOut);
                    System.setErr(originalErr);
                }
                return null;
            }
        };
        new Thread(task).start();
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
        try (FileWriter writer = new FileWriter(".env")) {
            writer.write("API_KEY=" + apiKeyField.getText() + "\n");
            writer.write("DB_URL=" + dbUrlField.getText() + "\n");
            writer.write("DB_USER=" + dbUserField.getText() + "\n");
            writer.write("DB_PASSWORD=" + dbPasswordField.getText() + "\n");
            writer.write("DB_SCHEMA=" + dbSchemaField.getText() + "\n");
            writer.flush();
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
}