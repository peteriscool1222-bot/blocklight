package dev.mclauncher.ui;

import dev.mclauncher.App;
import dev.mclauncher.auth.Account;
import dev.mclauncher.core.LauncherCore;
import dev.mclauncher.core.LauncherPaths;
import dev.mclauncher.core.VersionChain;
import dev.mclauncher.storage.Settings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.util.List;

/** Main play screen: pick account + version, install-on-demand, launch. */
public final class PlayView extends VBox {
    private final App app;
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final ComboBox<Account> accountBox = new ComboBox<>();
    private final Button playButton = new Button("PLAY");
    private final Button stopButton = new Button("Stop");
    private final Label statusLabel = new Label("Ready");
    private final ProgressBar bar = new ProgressBar(0);

    private volatile Process process;
    private volatile boolean busy;

    private final javafx.beans.value.ChangeListener<String> versionListener;
    private final javafx.beans.value.ChangeListener<Account> accountListener;

    public PlayView(App app) {
        this.app = app;
        setSpacing(16);
        setAlignment(Pos.TOP_LEFT);

        versionListener = (obs, o, n) -> {
            if (n != null) { app.settings().defaultVersion = n; app.settings().save(); }
            updatePlayEnabled();
        };
        accountListener = (obs, o, n) -> {
            if (n != null) app.accounts().setDefault(n);
            updatePlayEnabled();
        };
        versionBox.valueProperty().addListener(versionListener);
        accountBox.valueProperty().addListener(accountListener);

        Label title = new Label("Play");
        title.getStyleClass().add("title");

        Node card = buildCard();

        getChildren().addAll(title, card);
        VBox.setVgrow(card, Priority.ALWAYS);

        refresh();
    }

    private Node buildCard() {
        Label verL = new Label("Version");
        verL.getStyleClass().add("muted");
        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.setPromptText("No versions installed");

        Label accL = new Label("Account");
        accL.getStyleClass().add("muted");
        accountBox.setMaxWidth(Double.MAX_VALUE);
        accountBox.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Account a, boolean empty) {
                super.updateItem(a, empty);
                setText(empty || a == null ? null : a.username + (a.isMicrosoft() ? "  (Microsoft)" : "  (offline)"));
            }
        });
        accountBox.setButtonCell(accountBox.getCellFactory().call(null));

        HBox versionRow = labeled(verL, versionBox);
        HBox accountRow = labeled(accL, accountBox);

        playButton.getStyleClass().add("primary");
        playButton.setMaxWidth(Double.MAX_VALUE);
        playButton.setStyle("-fx-font-size: 18px; -fx-padding: 14;");
        stopButton.getStyleClass().add("danger");
        stopButton.setVisible(false);
        stopButton.managedProperty().bind(stopButton.visibleProperty());
        stopButton.setOnAction(e -> {
            Process p = process;
            if (p != null) { p.destroyForcibly(); app.log("Game process killed."); }
        });

        // max, not pref: a pref of Double.MAX_VALUE overflows the parent's
        // preferred-width math as soon as the bar becomes visible and the
        // whole view collapses to its minimum width
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setVisible(false);
        bar.managedProperty().bind(bar.visibleProperty());

        statusLabel.getStyleClass().add("muted");

        HBox buttons = new HBox(10, playButton, stopButton);
        HBox.setHgrow(playButton, Priority.ALWAYS);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        VBox.setMargin(form, new Insets(0));
        form.add(versionRow, 0, 0);
        form.add(accountRow, 0, 1);
        HBox.setHgrow(versionRow, Priority.ALWAYS);
        HBox.setHgrow(accountRow, Priority.ALWAYS);

        VBox card = new VBox(12, form, bar, buttons, statusLabel);
        card.getStyleClass().add("card");
        card.setMaxWidth(Double.MAX_VALUE);

        playButton.setOnAction(e -> play());
        return card;
    }

    private static HBox labeled(Label l, Control c) {
        l.setMinWidth(70);
        return new HBox(10, l, c);
    }

    /** Reload version/account lists (call after installs or account changes). */
    public void refresh() {
        Settings s = app.settings();
        Path game = LauncherPaths.gameDir(s.gameDir);

        accountBox.getItems().setAll(app.accounts().all());
        Account def = app.accounts().defaultAccount();
        if (def != null) accountBox.setValue(def);

        playButton.setDisable(true);
        statusLabel.setText("Scanning installed versions...");
        new Thread(() -> {
            List<String> installed = Ui.installedVersions(game);
            App.ui(() -> {
                versionBox.getItems().setAll(installed);
                if (!installed.isEmpty()) {
                    String preferred = s.defaultVersion != null && installed.contains(s.defaultVersion)
                            ? s.defaultVersion : installed.get(0);
                    versionBox.setValue(preferred);
                    statusLabel.setText("Ready");
                } else {
                    statusLabel.setText("No versions installed — get some in the Versions tab.");
                }
                updatePlayEnabled();
            });
        }, "version-scan").start();

        updatePlayEnabled();
    }

    private void updatePlayEnabled() {
        playButton.setDisable(busy
                || versionBox.getValue() == null
                || accountBox.getValue() == null);
    }

    private void play() {
        if (busy) return;
        String versionId = versionBox.getValue();
        Account account = accountBox.getValue();
        Settings settings = app.settings();
        Path game = LauncherPaths.gameDir(settings.gameDir);

        busy = true;
        updatePlayEnabled();
        bar.setVisible(true);
        bar.setProgress(0);
        stopButton.setVisible(true);
        statusLabel.setText("Preparing " + versionId + "...");

        new Thread(() -> {
            try {
                app.log("Resolving " + versionId + "...");
                VersionChain chain = VersionChain.resolve(versionId, LauncherPaths.versions(game));

                java.util.concurrent.atomic.AtomicReference<Account> activeAcc =
                        new java.util.concurrent.atomic.AtomicReference<>(account);
                if (account.isMicrosoft() && account.expired()) {
                    app.log("Access token expired, refreshing...");
                    Account fresh = dev.mclauncher.auth.MicrosoftAuth.refresh(account);
                    app.accounts().add(fresh);
                    App.ui(() -> {
                        accountBox.getItems().setAll(app.accounts().all());
                        accountBox.setValue(fresh);
                    });
                    activeAcc.set(fresh);
                }

                LauncherCore.install(chain, game,
                        p -> App.ui(() -> bar.setProgress(p)),
                        s -> App.ui(() -> statusLabel.setText(s)));

                // install finished — retire the bar instead of leaving it frozen
                // on screen for the entire game session
                App.ui(() -> {
                    bar.setVisible(false);
                    bar.setProgress(0);
                    statusLabel.setText("Launching...");
                });
                Process p = LauncherCore.launch(chain, game, activeAcc.get(), settings, this::appendLog);
                process = p;
                LauncherCore.pump(p, this::appendLog);
                App.ui(() -> statusLabel.setText("Game running."));
                int code = p.waitFor();
                app.log("Game exited with code " + code);
                App.ui(() -> statusLabel.setText(code == 0 ? "Game closed." : "Game crashed (exit " + code + ") — see console."));
            } catch (Exception ex) {
                app.log("ERROR: " + ex.getMessage());
                App.ui(() -> statusLabel.setText("Error: " + ex.getMessage()));
            } finally {
                process = null;
                App.ui(() -> {
                    busy = false;
                    bar.setVisible(false);
                    stopButton.setVisible(false);
                    updatePlayEnabled();
                });
            }
        }, "launch").start();
    }

    private void appendLog(String line) {
        app.log(line);
    }
}
