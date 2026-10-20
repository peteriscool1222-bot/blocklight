package dev.mclauncher.ui;

import dev.mclauncher.App;
import com.google.gson.JsonParser;
import dev.mclauncher.core.*;
import dev.mclauncher.install.FabricInstaller;
import dev.mclauncher.install.ForgeInstaller;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Browse all Minecraft versions; install vanilla, Fabric, or Forge. */
public final class VersionsView extends HBox {
    private final App app;
    private final ListView<String> list = new ListView<>();
    private final Label detailTitle = new Label("Select a version");
    private final Label detailMeta = new Label("");
    private final VBox detailActions = new VBox(8);
    private final Label statusLabel = new Label("");
    private final ProgressBar bar = new ProgressBar(0);
    private final CheckBox snapshotsBox = new CheckBox("Show snapshots");
    private final TextField searchBox = new TextField();

    private List<VersionManifest.VersionEntry> entries = List.of();
    private final AtomicBoolean loading = new AtomicBoolean();

    public VersionsView(App app) {
        this.app = app;
        setSpacing(18);

        searchBox.setPromptText("Search versions...");
        searchBox.setMaxWidth(Double.MAX_VALUE);
        snapshotsBox.setSelected(app.settings().showSnapshots);
        snapshotsBox.setOnAction(e -> {
            app.settings().showSnapshots = snapshotsBox.isSelected();
            app.settings().save();
            reload();
        });

        list.setPrefWidth(300);
        VBox.setVgrow(list, Priority.ALWAYS);
        list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> { if (n != null) showDetail(n); });
        searchBox.textProperty().addListener((obs, o, n) -> filter());

        Label title = new Label("Versions");
        title.getStyleClass().add("title");

        VBox left = new VBox(10, title, searchBox, snapshotsBox, list);
        left.setPrefWidth(330);

        detailActions.setMaxWidth(520);
        bar.setMaxWidth(520);
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

    private void reload() {
        list.getItems().setAll("Loading version list...");
        new Thread(() -> {
            try {
                List<VersionManifest.VersionEntry> all = VersionManifest.versions();
                App.ui(() -> {
                    entries = all;
                    filter();
                    warmMeta(all);
                });
            } catch (Exception e) {
                App.ui(() -> list.getItems().setAll("Failed to load: " + e.getMessage()));
            }
        }, "manifest").start();
    }

    /**
     * Preload fabric/forge metadata for the versions users actually click (their default
     * and the newest release) in the background, so opening the detail pane never waits
     * on the network. Failures are simply not cached — the real click retries them.
     */
    private void warmMeta(List<VersionManifest.VersionEntry> all) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        String def = app.settings().defaultVersion;
        if (def != null && !def.isBlank() && all.stream().anyMatch(v -> v.id().equals(def))) ids.add(def);
        all.stream().filter(v -> v.type().equals("release")).findFirst()
                .map(VersionManifest.VersionEntry::id).ifPresent(ids::add);
        if (ids.isEmpty()) return;
        new Thread(() -> {
            for (String id : ids) {
                try { FabricInstaller.loaders(id); } catch (Exception ignored) {}
                try { ForgeInstaller.builds(id); } catch (Exception ignored) {}
            }
        }, "meta-warm").start();
    }

    private void filter() {
        String q = searchBox.getText() == null ? "" : searchBox.getText().trim().toLowerCase();
        List<String> shown = entries.stream()
                .filter(v -> snapshotsBox.isSelected() || v.type().equals("release"))
                .filter(v -> q.isEmpty() || v.id().toLowerCase().contains(q))
                .map(VersionManifest.VersionEntry::id)
                .toList();
        list.getItems().setAll(shown);
    }

    private void showDetail(String id) {
        VersionManifest.VersionEntry entry = entries.stream()
                .filter(v -> v.id().equals(id)).findFirst().orElse(null);
        detailTitle.setText(id);
        detailMeta.setText(entry == null ? "Local version" : entry.type() + "  ·  " + entry.releaseTime());
        detailActions.getChildren().clear();

        Path game = LauncherPaths.gameDir(app.settings().gameDir);
        Path versionsDir = LauncherPaths.versions(game);
        boolean installedVanilla = java.nio.file.Files.exists(versionsDir.resolve(id).resolve(id + ".jar"));

        Button vanilla = new Button(installedVanilla ? "Vanilla installed ✓" : "Install Vanilla");
        vanilla.setDisable(entry == null || installedVanilla || loading.get());
        vanilla.setMaxWidth(260);
        vanilla.setOnAction(e -> runInstall(id, (progress, status) -> {
            java.nio.file.Files.createDirectories(versionsDir.resolve(id));
            status.accept("Fetching version metadata...");
            var json = Http.getJson(entry.url());
            java.nio.file.Files.writeString(versionsDir.resolve(id).resolve(id + ".json"), json.toString());
            // download the actual game files (client jar, libraries, natives, assets)
            LauncherCore.install(VersionChain.resolve(id, versionsDir), game, progress, status);
            return id;
        }, "Installing vanilla " + id));

        detailActions.getChildren().add(vanilla);
        detailActions.getChildren().add(buildFabricSection(id, versionsDir));
        detailActions.getChildren().add(buildForgeSection(id, game));
    }

    private Node buildFabricSection(String mcVersion, Path versionsDir) {
        String installedProfile = installedProfile(versionsDir, mcVersion, true);
        String installedLoader = installedProfile == null ? null : loaderVersionOf(installedProfile, mcVersion);
        Label l = new Label(installedProfile != null ? "Fabric  ·  installed ✓" : "Fabric");
        l.getStyleClass().add("h2");
        ComboBox<FabricInstaller.Loader> loaders = new ComboBox<>();
        loaders.setMaxWidth(200);
        loaders.setPromptText("Loading...");
        Button install = new Button("Install Fabric");
        install.setMaxWidth(260);
        install.setDisable(true);

        // installed + selected = done; anything else offers a (re)install
        Runnable sync = () -> {
            FabricInstaller.Loader sel = loaders.getValue();
            if (installedLoader != null && sel != null && sel.version().equals(installedLoader)) {
                install.setText("Fabric installed ✓");
                install.setDisable(true);
            } else {
                install.setText("Install Fabric");
                install.setDisable(sel == null || loading.get());
            }
        };

        loaders.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(FabricInstaller.Loader it, boolean empty) {
                super.updateItem(it, empty);
                setText(empty || it == null ? null : it.version() + (it.stable() ? "  (stable)" : ""));
            }
        });
        loaders.setButtonCell(loaders.getCellFactory().call(null));
        loaders.valueProperty().addListener((obs, o, n) -> sync.run());
        install.setOnAction(e -> runInstall(mcVersion, (progress, status) -> {
            status.accept("Fetching Fabric profile...");
            String profileId = FabricInstaller.install(mcVersion, loaders.getValue().version(), versionsDir);
            status.accept("Downloading Fabric files...");
            LauncherCore.install(VersionChain.resolve(profileId, versionsDir), versionsDir.getParent(),
                    progress, status);
            return profileId;
        }, "Installing Fabric for " + mcVersion));

        new Thread(() -> {
            try {
                List<FabricInstaller.Loader> ls = FabricInstaller.loaders(mcVersion);
                App.ui(() -> {
                    if (ls.isEmpty()) {
                        loaders.setPromptText("Fabric doesn't support this version");
                        sync.run();
                        return;
                    }
                    loaders.getItems().setAll(ls);
                    // open on the installed loader so the button shows its state
                    loaders.setValue(ls.stream()
                            .filter(x -> x.version().equals(installedLoader))
                            .findFirst().orElse(ls.get(0)));
                    sync.run();
                });
            } catch (Exception ex) {
                App.ui(() -> statusLabel.setText("Fabric meta: " + ex.getMessage()));
            }
        }, "fabric-meta").start();

        HBox row = new HBox(10, loaders, install);
        return new VBox(6, l, row);
    }

    private Node buildForgeSection(String mcVersion, Path game) {
        Path versionsDir = LauncherPaths.versions(game);
        String installedProfile = installedProfile(versionsDir, mcVersion, false);
        Label l = new Label(installedProfile != null ? "Forge  ·  installed ✓" : "Forge");
        l.getStyleClass().add("h2");
        ComboBox<String> builds = new ComboBox<>();
        builds.setMaxWidth(260);
        builds.setPromptText("Loading...");
        Button install = new Button("Install Forge (official installer)");
        install.setMaxWidth(260);
        install.setDisable(true);
        // installed + selected = done; picking another build offers a reinstall
        Runnable sync = () -> {
            String sel = builds.getValue();
            if (installedProfile != null && installedProfile.equals(sel)) {
                install.setText("Forge installed ✓");
                install.setDisable(true);
            } else {
                install.setText("Install Forge (official installer)");
                install.setDisable(sel == null || loading.get());
            }
        };
        builds.valueProperty().addListener((obs, o, n) -> sync.run());
        install.setOnAction(e -> runInstall(mcVersion, (progress, status) -> {
            status.accept("Running the official Forge installer...");
            String profileId = ForgeInstaller.install(builds.getValue(), game,
                    LauncherPaths.ROOT.resolve("forge-work"), app.settings(), app::log);
            status.accept("Downloading Forge libraries...");
            LauncherCore.install(VersionChain.resolve(profileId, LauncherPaths.versions(game)),
                    game, progress, status);
            return profileId;
        }, "Installing Forge " + builds.getValue()));

        new Thread(() -> {
            try {
                List<String> bs = ForgeInstaller.builds(mcVersion);
                App.ui(() -> {
                    if (bs.isEmpty()) {
                        builds.setPromptText("Forge doesn't support this version");
                        sync.run();
                        return;
                    }
                    builds.getItems().setAll(bs);
                    // open on the installed build so the button shows its state
                    builds.setValue(installedProfile != null && bs.contains(installedProfile)
                            ? installedProfile : bs.get(0));
                    sync.run();
                });
            } catch (Exception ex) {
                App.ui(() -> statusLabel.setText("Forge maven: " + ex.getMessage()));
            }
        }, "forge-meta").start();

        HBox row = new HBox(10, builds, install);
        return new VBox(6, l, row);
    }

    /**
     * Profile id of an already-installed Fabric/Forge profile inheriting from this
     * MC version, or null. Detection mirrors the Installations tab: the profile json's
     * inheritsFrom is the MC version (the profile folder holds no jar of its own).
     */
    private static String installedProfile(Path versionsDir, String mcVersion, boolean fabric) {
        try (var dirs = java.nio.file.Files.list(versionsDir)) {
            return dirs.filter(java.nio.file.Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(id -> !id.equals(mcVersion))
                    .sorted()
                    .filter(id -> {
                        Path j = versionsDir.resolve(id).resolve(id + ".json");
                        if (!java.nio.file.Files.exists(j)) return false;
                        try {
                            var root = JsonParser.parseString(java.nio.file.Files.readString(j)).getAsJsonObject();
                            if (!mcVersion.equals(Jsons.str(root, "inheritsFrom", ""))) return false;
                            if (fabric) return id.startsWith("fabric-loader-") || id.contains("fabric");
                            return !id.startsWith("neoforge")
                                    && (id.contains("forge") || id.startsWith(mcVersion + "-"));
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** "fabric-loader-0.19.5-26.3" + "26.3" -> "0.19.5" */
    private static String loaderVersionOf(String profileId, String mcVersion) {
        String s = profileId.startsWith("fabric-loader-")
                ? profileId.substring("fabric-loader-".length()) : profileId;
        String suffix = "-" + mcVersion;
        if (s.endsWith(suffix)) s = s.substring(0, s.length() - suffix.length());
        return s;
    }

    /** Runs an install job off the UI thread, driving the progress bar with real values. */
    private void runInstall(String label, InstallJob job, String startMessage) {
        if (!loading.compareAndSet(false, true)) return;
        bar.setVisible(true);
        bar.setProgress(-1);
        statusLabel.setText(startMessage);
        app.log(startMessage + "...");
        new Thread(() -> {
            try {
                String result = job.run(
                        p -> App.ui(() -> bar.setProgress(p)),
                        s -> App.ui(() -> statusLabel.setText(s)));
                loading.set(false);
                app.log("Installed: " + result);
                App.ui(() -> {
                    statusLabel.setText("Installed " + result + " — select it on the Play tab.");
                    bar.setVisible(false);
                    bar.setProgress(0);
                    // refresh the version we installed FROM (result is the new
                    // profile id — showing that would leave the section empty)
                    showDetail(label);
                });
            } catch (Exception ex) {
                loading.set(false);
                app.log("Install failed: " + ex.getMessage());
                App.ui(() -> {
                    statusLabel.setText("Install failed: " + ex.getMessage());
                    bar.setVisible(false);
                    bar.setProgress(0);
                });
            }
        }, "install").start();
    }

    @FunctionalInterface
    private interface InstallJob {
        String run(java.util.function.DoubleConsumer progress,
                   java.util.function.Consumer<String> status) throws Exception;
    }
}
