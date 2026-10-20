package dev.mclauncher.ui;

import dev.mclauncher.App;
import dev.mclauncher.core.LauncherCore;
import dev.mclauncher.core.LauncherPaths;
import dev.mclauncher.core.Os;
import dev.mclauncher.storage.Settings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** JVM / launcher settings. */
public final class SettingsView extends VBox {
    private final App app;

    private final TextField javaPath = new TextField();
    private final Spinner<Integer> minMem = intSpinner(128, 65536, 512, 128);
    private final Spinner<Integer> maxMem = intSpinner(512, 65536, 4096, 256);
    private final TextField jvmArgs = new TextField();
    private final TextField wrapperCommand = new TextField();
    private final TextField gameDir = new TextField();
    private final Spinner<Integer> width = intSpinner(640, 7680, 1280, 10);
    private final Spinner<Integer> height = intSpinner(480, 4320, 720, 10);
    private final CheckBox fullscreen = new CheckBox("Launch fullscreen");
    private final CheckBox keepOpen = new CheckBox("Keep launcher open while playing");
    private final CheckBox forceDgpu = new CheckBox("Force dedicated GPU");
    private final CheckBox perfTweaks = new CheckBox("Performance mode (Aikar's GC flags)");
    private final CheckBox gameMode = new CheckBox("GameMode (gamemoderun)");
    private final Label savedLabel = new Label("");

    public SettingsView(App app) {
        this.app = app;
        setSpacing(14);

        Label title = new Label("Settings");
        title.getStyleClass().add("title");

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.setMaxWidth(720);

        javaPath.setPromptText("Auto-detect (current Java)");
        javaPath.setMaxWidth(Double.MAX_VALUE);
        Button browse = new Button("Browse...");
        browse.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("Select java executable");
            var f = fc.showOpenDialog(getScene().getWindow());
            if (f != null) javaPath.setText(f.getAbsolutePath());
        });
        Button useCurrent = new Button("Use current JVM");
        useCurrent.setOnAction(e -> javaPath.setText(System.getProperty("java.home")));
        HBox javaRow = new HBox(8, javaPath, browse, useCurrent);
        HBox.setHgrow(javaPath, Priority.ALWAYS);

        gameDir.setPromptText(LauncherPaths.ROOT.resolve("game").toString());
        gameDir.setMaxWidth(Double.MAX_VALUE);
        Button dirBrowse = new Button("Browse...");
        dirBrowse.setOnAction(e -> {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
            dc.setTitle("Select installation directory");
            var f = dc.showDialog(getScene().getWindow());
            if (f != null) gameDir.setText(f.getAbsolutePath());
        });
        Button openRoot = new Button("Open folder");
        openRoot.setOnAction(e -> Ui.openFolder(LauncherPaths.gameDir(gameDir.getText()), false));
        Button openMods = new Button("Open mods folder");
        openMods.setOnAction(e -> Ui.openFolder(LauncherPaths.gameDir(gameDir.getText()).resolve("mods"), true));
        HBox dirRow = new HBox(8, gameDir, dirBrowse, openRoot, openMods);
        HBox.setHgrow(gameDir, Priority.ALWAYS);
        Label dirHint = hint("Each version gets its own versions/<id> subfolder — vanilla, Fabric and "
                + "Forge coexist without overwriting each other. Blank = default path.");

        jvmArgs.setPromptText("-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions ...");
        jvmArgs.setMaxWidth(Double.MAX_VALUE);

        wrapperCommand.setPromptText("mangohud --dlsym");
        wrapperCommand.setMaxWidth(Double.MAX_VALUE);
        Label wrapperHint = hint("Runs in front of the game command, e.g. mangohud --dlsym. Blank = none.");

        HBox resRow = new HBox(8, width, new Label("×"), height);
        resRow.setAlignment(Pos.CENTER_LEFT);

        int row = 0;
        add(grid, row++, "Java path", javaRow);
        add(grid, row++, "Memory (MB)", new HBox(10, labeled("Min", minMem), labeled("Max", maxMem)));
        add(grid, row++, "Extra JVM args", jvmArgs);
        add(grid, row++, "Wrapper command", new VBox(4, wrapperCommand, wrapperHint));
        add(grid, row++, "Installation directory", new VBox(4, dirRow, dirHint));
        add(grid, row++, "Resolution", resRow);
        add(grid, row++, "", fullscreen);
        add(grid, row++, "", keepOpen);

        Label gpuHint = hint(switch (Os.NAME) {
            case "linux" -> "PRIME render-offload: runs the game on the NVIDIA dGPU instead of Mesa "
                    + "or software rendering.";
            case "windows" -> "Sets the per-app “High performance GPU” preference for Java, same as "
                    + "Windows Graphics Settings.";
            default -> "Not applicable on this OS.";
        });
        if (Os.NAME.equals("osx")) forceDgpu.setDisable(true);
        add(grid, row++, "", new VBox(4, forceDgpu, gpuHint));

        Label perfHint = hint("Aikar's tuned G1GC flags: fewer frame-time spikes while chunks load. "
                + "Auto-skips if you set your own GC flag below.");
        add(grid, row++, "", new VBox(4, perfTweaks, perfHint));

        boolean gmAvailable = LauncherCore.gameModeRunner() != null;
        Label gmHint = hint(gmAvailable
                ? "gamemoderun detected — performance CPU governor, I/O priority and scheduler tuning "
                + "while the game runs."
                : "Not installed — install the 'gamemode' package. Skipped automatically at launch.");
        if (!gmAvailable) gameMode.setDisable(true);
        add(grid, row++, "", new VBox(4, gameMode, gmHint));

        Button save = new Button("Save settings");
        save.getStyleClass().add("primary");
        save.setOnAction(e -> save());
        savedLabel.getStyleClass().add("muted");

        Label hint = hint("Pre-1.17 versions usually need an older Java (8–17) — point “Java path” at it.");

        getChildren().addAll(title, grid, new HBox(12, save, savedLabel), hint);
        load();
    }

    private static void add(GridPane grid, int row, String label, javafx.scene.Node node) {
        Label l = new Label(label);
        l.getStyleClass().add("muted");
        l.setMinWidth(110);
        grid.add(l, 0, row);
        if (node instanceof Region r) {
            // let the value column stretch so fields fill the grid up to its max width
            r.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(r, Priority.ALWAYS);
        }
        grid.add(node, 1, row);
    }

    /** Muted, wrapped hint with a hard width cap — without the cap, the unwrapped
     *  pref width would blow the grid out and the row could never fill correctly. */
    private static Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        l.setWrapText(true);
        l.setMaxWidth(580);
        return l;
    }

    private static HBox labeled(String label, Control c) {
        Label l = new Label(label);
        l.getStyleClass().add("muted");
        return new HBox(6, l, c);
    }

    private static Spinner<Integer> intSpinner(int min, int max, int value, int step) {
        Spinner<Integer> s = new Spinner<>(min, max, value, step);
        s.setEditable(true);
        s.setPrefWidth(130);
        return s;
    }

    private void load() {
        Settings s = app.settings();
        javaPath.setText(s.javaPath);
        minMem.getValueFactory().setValue(s.minMemoryMB);
        maxMem.getValueFactory().setValue(s.maxMemoryMB);
        jvmArgs.setText(s.jvmArgs);
        wrapperCommand.setText(s.wrapperCommand);
        gameDir.setText(s.gameDir);
        width.getValueFactory().setValue(s.width);
        height.getValueFactory().setValue(s.height);
        fullscreen.setSelected(s.fullscreen);
        keepOpen.setSelected(s.keepOpen);
        forceDgpu.setSelected(s.forceDgpu);
        perfTweaks.setSelected(s.perfTweaks);
        gameMode.setSelected(s.gameMode);
    }

    private void save() {
        Settings s = app.settings();
        s.javaPath = javaPath.getText() == null ? "" : javaPath.getText().trim();
        s.minMemoryMB = minMem.getValue();
        s.maxMemoryMB = Math.max(minMem.getValue(), maxMem.getValue());
        s.jvmArgs = jvmArgs.getText() == null ? "" : jvmArgs.getText().trim();
        s.wrapperCommand = wrapperCommand.getText() == null ? "" : wrapperCommand.getText().trim();
        s.gameDir = gameDir.getText() == null ? "" : gameDir.getText().trim();
        s.width = width.getValue();
        s.height = height.getValue();
        s.fullscreen = fullscreen.isSelected();
        s.keepOpen = keepOpen.isSelected();
        s.forceDgpu = forceDgpu.isSelected();
        s.perfTweaks = perfTweaks.isSelected();
        s.gameMode = gameMode.isSelected();
        s.save();
        app.log("Settings saved.");
        savedLabel.setText("Saved ✓");
        javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
        t.setOnFinished(e -> savedLabel.setText(""));
        t.play();
    }
}
