package dev.mclauncher;

import dev.mclauncher.auth.AccountManager;
import dev.mclauncher.core.LauncherPaths;
import dev.mclauncher.storage.Settings;
import dev.mclauncher.ui.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

public class App extends Application {
    private Settings settings;
    private AccountManager accounts;

    private VBox sidebar;
    private StackPane content;
    private ToggleButton navPlay, navVersions, navInstallations, navAccounts, navSettings;
    private TextArea console;
    private VBox consoleBox;
    private boolean consoleVisible = true;

    public Settings settings() { return settings; }
    public AccountManager accounts() { return accounts; }

    @Override
    public void start(Stage stage) {
        settings = Settings.load();
        accounts = new AccountManager();

        // glowstone window/taskbar icon (default would be the Java logo)
        try (var in = getClass().getResourceAsStream("/icon.png")) {
            if (in != null) stage.getIcons().add(new javafx.scene.image.Image(in));
        } catch (Exception ignored) {}

        try {
            java.nio.file.Files.createDirectories(LauncherPaths.ROOT);
        } catch (Exception ignored) {}

        sidebar = new VBox(4);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(190);
        sidebar.setPadding(new Insets(16, 10, 16, 10));

        Label brand = new Label("⛏  Blocklight");
        brand.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #8fd694; -fx-padding: 6 8 18 8;");

        navPlay = navButton("Play");
        navVersions = navButton("Versions");
        navInstallations = navButton("Installations");
        navAccounts = navButton("Accounts");
        navSettings = navButton("Settings");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        ToggleButton consoleToggle = navButton("Console");
        consoleToggle.setSelected(true);
        consoleToggle.setOnAction(e -> toggleConsole());

        Label version = new Label("Blocklight 1.0");
        version.getStyleClass().add("muted");
        version.setStyle("-fx-padding: 8 8 0 8;");

        sidebar.getChildren().addAll(brand, navPlay, navVersions, navInstallations, navAccounts, navSettings, spacer, consoleToggle, version);

        content = new StackPane();
        content.setPadding(new Insets(20, 24, 12, 24));

        console = new TextArea();
        console.setEditable(false);
        console.setWrapText(false);
        console.setPrefRowCount(9);
        consoleBox = new VBox(console);
        consoleBox.setPadding(new Insets(0, 24, 16, 24));
        VBox.setVgrow(console, Priority.ALWAYS);

        Label consoleTitle = new Label("Output");
        consoleTitle.getStyleClass().add("muted");
        consoleBox.getChildren().add(0, consoleTitle);

        VBox root = new VBox();
        root.getStyleClass().add("bg");
        HBox body = new HBox(sidebar, content);
        HBox.setHgrow(content, Priority.ALWAYS);
        root.getChildren().addAll(body, consoleBox);
        VBox.setVgrow(body, Priority.ALWAYS);

        navPlay.setOnAction(e -> show(new PlayView(this)));
        navVersions.setOnAction(e -> show(new VersionsView(this)));
        navInstallations.setOnAction(e -> show(new InstallationsView(this)));
        navAccounts.setOnAction(e -> show(new AccountsView(this)));
        navSettings.setOnAction(e -> show(new SettingsView(this)));

        Scene scene = new Scene(root, 1080, 760);
        scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
        stage.setTitle("Blocklight — Minecraft Launcher");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.show();

        show(new PlayView(this));
        log("Launcher ready. Data dir: " + LauncherPaths.ROOT);
    }

    private ToggleButton navButton(String text) {
        ToggleButton b = new ToggleButton(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("nav-button");
        return b;
    }

    private void show(javafx.scene.Node node) {
        for (ToggleButton b : new ToggleButton[]{navPlay, navVersions, navInstallations, navAccounts, navSettings})
            b.setSelected(false);
        ToggleButton active = switch (node) {
            case PlayView p -> navPlay;
            case VersionsView v -> navVersions;
            case InstallationsView i -> navInstallations;
            case AccountsView a -> navAccounts;
            default -> navSettings;
        };
        active.setSelected(true);
        content.getChildren().setAll(node);
    }

    private void toggleConsole() {
        consoleVisible = !consoleVisible;
        consoleBox.setVisible(consoleVisible);
        consoleBox.setManaged(consoleVisible);
    }
    /** Thread-safe log to the console pane. */
    public void log(String line) {
        Platform.runLater(() -> {
            console.appendText(line + "\n");
            console.positionCaret(console.getLength());
        });
        System.out.println(line);
    }

    public static void ui(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }
}
