package dev.mclauncher.ui;

import dev.mclauncher.App;
import dev.mclauncher.core.LauncherPaths;
import dev.mclauncher.core.VersionChain;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Manage what is installed: list every version folder (vanilla / Fabric / Forge,
 * including broken leftovers), show sizes, delete installations, and run
 * maintenance on the shared downloads. Each version lives in its own
 * versions/&lt;id&gt; folder, so deleting one never touches the others.
 */
public final class InstallationsView extends HBox {
    private record Install(String id, Path dir, String kind, boolean complete, long size) {}

    private final App app;
    private final ListView<Install> list = new ListView<>();
    private final Label dirLabel = new Label("");
    private final Label detailTitle = new Label("Select an installation");
    private final Label detailMeta = new Label("");
    private final VBox detailActions = new VBox(8);
    private final Label statusLabel = new Label("");
    private final Button refreshBtn = new Button("Refresh");
    private final javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(0);
    private final java.util.concurrent.atomic.AtomicBoolean importing = new java.util.concurrent.atomic.AtomicBoolean();

    private Path game;

    public InstallationsView(App app) {
        this.app = app;
        setSpacing(18);

        game = LauncherPaths.gameDir(app.settings().gameDir);

        Label title = new Label("Installations");
        title.getStyleClass().add("title");

        dirLabel.getStyleClass().add("muted");
        dirLabel.setWrapText(true);

        list.setPrefWidth(340);
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Install it, boolean empty) {
                super.updateItem(it, empty);
                if (empty || it == null) { setText(null); setGraphic(null); return; }
                Label id = new Label(it.id());
                id.setStyle("-fx-font-weight: bold;");
                Label sub = new Label(it.kind() + "  ·  " + human(it.size())
                        + (it.complete() ? "" : "  ·  incomplete"));
                sub.getStyleClass().add("muted");
                setText(null);
                setGraphic(new VBox(1, id, sub));
            }
        });
        list.getSelectionModel().selectedItemProperty()
                .addListener((obs, o, n) -> { if (n != null) showDetail(n); });

        refreshBtn.setOnAction(e -> reload());

        Button openGame = new Button("Open game folder");
        openGame.setOnAction(e -> Ui.openFolder(game, false));
        Button openMods = new Button("Open mods folder");
        openMods.setOnAction(e -> Ui.openFolder(game.resolve("mods"), true));
        HBox leftTop = new HBox(10, refreshBtn, openGame, openMods);

        Button importBtn = new Button("Import .mrpack modpack...");
        importBtn.setMaxWidth(Double.MAX_VALUE);
        importBtn.setOnAction(e -> importMrpack());

        VBox left = new VBox(10, title, dirLabel, leftTop, importBtn, list);
        left.setPrefWidth(380);

        detailActions.setMaxWidth(540);
        bar.setMaxWidth(540);
        bar.setVisible(false);
        bar.managedProperty().bind(bar.visibleProperty());
        statusLabel.getStyleClass().add("muted");
        statusLabel.setWrapText(true);

        VBox right = new VBox(14, detailTitle, detailMeta, detailActions, bar, statusLabel);
        right.setFillWidth(true);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox.setMargin(detailActions, new Insets(6, 0, 0, 0));

        getChildren().addAll(left, right);
        reload();
    }

    // ----------------------------------------------------------------- loading

    private void reload() { reload(null); }

    /** Reload the list; when {@code after} is set it replaces the scan summary line. */
    private void reload(String after) {
        game = LauncherPaths.gameDir(app.settings().gameDir);
        dirLabel.setText("Installation directory: " + game);
        list.getItems().setAll();
        detailTitle.setText("Select an installation");
        detailMeta.setText("");
        detailActions.getChildren().clear();
        statusLabel.setText("Scanning...");
        refreshBtn.setDisable(true);

        new Thread(() -> {
            List<Install> found = scan();
            long shared = dirSize(LauncherPaths.libraries(game)) + dirSize(LauncherPaths.assets(game));
            App.ui(() -> {
                refreshBtn.setDisable(false);
                list.getItems().setAll(found);
                if (after != null) {
                    statusLabel.setText(after);
                } else if (found.isEmpty()) {
                    statusLabel.setText("Nothing installed yet — install versions in the Versions tab.");
                } else {
                    statusLabel.setText(found.size() + " installation(s)  ·  shared downloads: "
                            + human(shared));
                }
            });
        }, "install-scan").start();
    }

    /** Every version folder, including broken/incomplete leftovers. */
    private List<Install> scan() {
        List<Install> out = new ArrayList<>();
        Path versionsDir = LauncherPaths.versions(game);
        if (!Files.isDirectory(versionsDir)) return out;
        try (var dirs = Files.list(versionsDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String id = dir.getFileName().toString();
                boolean hasJson = Files.exists(dir.resolve(id + ".json"));
                boolean hasJar = Files.exists(dir.resolve(id + ".jar"));
                Path instance = LauncherPaths.instanceDir(game, id);
                if (!hasJson && !hasJar && instance == null) continue; // not a version folder (e.g. crash-reports)
                // complete = actually launchable: the resolved chain's jar exists.
                // Fabric/Forge profiles have no jar of their own — theirs is the
                // vanilla parent's — so a same-folder jar check always lied.
                boolean complete = false;
                if (hasJson) {
                    try {
                        VersionChain chain = VersionChain.resolve(id, versionsDir);
                        complete = Files.exists(Ui.LauncherCoreJars.jar(chain, game));
                    } catch (Exception ignored) {
                        // unresolvable chain (missing parent json etc.)
                    }
                }
                long size = dirSize(dir) + (instance != null ? dirSize(instance) : 0);
                out.add(new Install(id, dir, instance != null ? "Modpack" : kind(id, versionsDir), complete, size));
            }
        } catch (Exception ignored) {}
        out.sort(Comparator.comparing((Install i) -> i.id().toLowerCase()).reversed());
        return out;
    }

    /** Vanilla / Fabric / Forge, inferred from id and resolved main class. */
    private String kind(String id, Path versionsDir) {
        String lower = id.toLowerCase();
        if (lower.contains("fabric")) return "Fabric";
        if (lower.contains("forge")) return "Forge";
        try {
            VersionChain chain = VersionChain.resolve(id, versionsDir);
            String mc = chain.mainClass() == null ? "" : chain.mainClass().toLowerCase();
            if (mc.contains("fabric") || mc.contains("knot")) return "Fabric";
            if (mc.contains("forge") || mc.contains("minecraftforge")) return "Forge";
        } catch (Exception ignored) {}
        return "Vanilla";
    }

    // ------------------------------------------------------------------ import

    /** File picker → import the chosen .mrpack as an isolated instance, with live progress. */
    private void importMrpack() {
        if (!importing.compareAndSet(false, true)) return;
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Import Modrinth modpack");
        fc.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter("Modrinth modpack (*.mrpack, *.zip)", "*.mrpack", "*.zip"),
                new javafx.stage.FileChooser.ExtensionFilter("All files", "*.*"));
        java.io.File f = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (f == null) {
            importing.set(false);
            return;
        }
        bar.setVisible(true);
        bar.setProgress(-1);
        statusLabel.setText("Importing " + f.getName() + "...");
        app.log("Importing modpack " + f.getName() + "...");
        new Thread(() -> {
            try {
                String id = dev.mclauncher.install.MrpackInstaller.install(
                        f.toPath(), game, app.settings(), app::log,
                        p -> App.ui(() -> bar.setProgress(p)),
                        s -> App.ui(() -> statusLabel.setText(s)));
                importing.set(false);
                app.log("Imported modpack: " + id);
                App.ui(() -> {
                    bar.setVisible(false);
                    bar.setProgress(0);
                    reload("Imported " + id + " — select it on the Play tab.");
                });
            } catch (Exception ex) {
                importing.set(false);
                app.log("Modpack import failed: " + ex.getMessage());
                App.ui(() -> {
                    bar.setVisible(false);
                    bar.setProgress(0);
                    statusLabel.setText("Import failed: " + ex.getMessage());
                });
            }
        }, "mrpack-import").start();
    }

    // ------------------------------------------------------------------ detail

    private void showDetail(Install it) {
        Path instance = LauncherPaths.instanceDir(game, it.id());
        detailTitle.setText(it.id());
        detailMeta.setText(it.kind() + "  ·  " + human(it.size()) + "  ·  "
                + (it.complete() ? "complete" : "incomplete (missing game files)") + "\n" + it.dir()
                + (instance != null ? "\n" + instance : ""));
        detailActions.getChildren().clear();

        boolean isDefault = it.id().equals(app.settings().defaultVersion);

        Button def = new Button(isDefault ? "Default version ✓" : "Set as default version");
        def.setDisable(isDefault);
        def.setMaxWidth(300);
        def.setOnAction(e -> {
            app.settings().defaultVersion = it.id();
            app.settings().save();
            statusLabel.setText("Default version set to " + it.id());
            showDetail(it);
        });

        Button open = new Button("Open folder");
        open.setMaxWidth(300);
        open.setOnAction(e -> Ui.openFolder(it.dir(), false));

        detailActions.getChildren().addAll(def, open);
        if (instance != null) {
            Button openPack = new Button("Open pack folder (mods / config / saves)");
            openPack.setMaxWidth(300);
            openPack.setOnAction(e -> Ui.openFolder(instance, false));
            detailActions.getChildren().add(openPack);
        }
        Button del = new Button("Delete installation...");
        del.getStyleClass().add("danger");
        del.setMaxWidth(300);
        del.setOnAction(e -> deleteInstall(it));

        detailActions.getChildren().addAll(del, maintenance());
    }

    /** Shared-disk maintenance: clear libraries/assets/caches. */
    private Node maintenance() {
        Label h = new Label("Maintenance");
        h.getStyleClass().add("h2");

        Button clearShared = new Button("Clear shared downloads (libraries & assets)...");
        clearShared.getStyleClass().add("danger");
        clearShared.setMaxWidth(300);
        clearShared.setOnAction(e -> {
            long sz = dirSize(LauncherPaths.libraries(game)) + dirSize(LauncherPaths.assets(game));
            Alert c = new Alert(Alert.AlertType.CONFIRMATION);
            c.setTitle("Clear shared downloads");
            c.setHeaderText("Delete " + human(sz) + " of shared downloads?");
            c.setContentText("libraries/ and assets/ will be removed. Every installed version stays\n"
                    + "listed, and the files are downloaded again automatically on next launch.");
            c.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.OK) return;
                statusLabel.setText("Clearing shared downloads...");
                new Thread(() -> {
                    try {
                        deleteRec(LauncherPaths.libraries(game));
                        deleteRec(LauncherPaths.assets(game));
                        app.log("Cleared shared downloads (" + human(sz) + ")");
                        App.ui(() -> statusLabel.setText("Shared downloads cleared (" + human(sz)
                                + ") — they re-download on next launch."));
                    } catch (Exception ex) {
                        App.ui(() -> statusLabel.setText("Failed: " + ex.getMessage()));
                    }
                }, "clear-shared").start();
            });
        });

        Button clearCache = new Button("Clear metadata caches");
        clearCache.setMaxWidth(300);
        clearCache.setOnAction(e -> {
            try {
                deleteRec(LauncherPaths.ROOT.resolve("cache"));
                app.log("Metadata caches cleared.");
                statusLabel.setText("Metadata caches cleared — version list refreshes on next open.");
            } catch (Exception ex) {
                statusLabel.setText("Failed: " + ex.getMessage());
            }
        });

        return new VBox(6, h, clearShared, clearCache);
    }

    private void deleteInstall(Install it) {
        Path instance = LauncherPaths.instanceDir(game, it.id());
        Alert c = new Alert(Alert.AlertType.CONFIRMATION);
        c.setTitle("Delete installation");
        c.setHeaderText("Delete " + it.id() + "? (" + human(it.size()) + ")");
        c.setContentText(instance != null
                ? "versions/" + it.id() + " and the pack folder\n" + instance + "\n"
                  + "are removed (mods, config, saves).\n"
                  + "Shared downloads and other versions are untouched."
                : "Only versions/" + it.id() + " is removed.\n"
                  + "Shared downloads, other versions, and your accounts are untouched —\n"
                  + "you can reinstall it any time from the Versions tab.");
        c.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            statusLabel.setText("Deleting " + it.id() + "...");
            new Thread(() -> {
                try {
                    deleteRec(it.dir());
                    if (instance != null) deleteRec(instance);
                    if (it.id().equals(app.settings().defaultVersion)) {
                        app.settings().defaultVersion = null;
                        app.settings().save();
                    }
                    app.log("Deleted installation " + it.id() + " (" + human(it.size()) + ")"
                            + (instance != null ? " incl. pack folder" : ""));
                    App.ui(() -> {
                        statusLabel.setText("Deleted " + it.id() + " (" + human(it.size()) + ")");
                        reload();
                    });
                } catch (Exception ex) {
                    App.ui(() -> statusLabel.setText("Delete failed: " + ex.getMessage()));
                }
            }, "delete-install").start();
        });
    }

    // ----------------------------------------------------------------- helpers

    private static long dirSize(Path p) {
        if (p == null || !Files.exists(p)) return 0;
        try (var walk = Files.walk(p)) {
            return walk.filter(Files::isRegularFile).mapToLong(f -> {
                try { return Files.size(f); } catch (Exception e) { return 0; }
            }).sum();
        } catch (Exception e) { return 0; }
    }

    private static void deleteRec(Path p) throws java.io.IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = -1;
        do { v /= 1024; u++; } while (v >= 1024 && u < units.length - 1);
        return String.format("%.1f %s", v, units[u]);
    }
}
