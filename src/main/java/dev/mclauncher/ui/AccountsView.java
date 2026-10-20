package dev.mclauncher.ui;

import dev.mclauncher.App;
import dev.mclauncher.auth.Account;
import dev.mclauncher.auth.MicrosoftAuth;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

/** Manage Microsoft and offline accounts. */
public final class AccountsView extends VBox {
    private final App app;
    private final ListView<Account> list = new ListView<>();

    public AccountsView(App app) {
        this.app = app;
        setSpacing(14);

        Label title = new Label("Accounts");
        title.getStyleClass().add("title");

        list.setPrefHeight(260);
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Account a, boolean empty) {
                super.updateItem(a, empty);
                if (empty || a == null) { setText(null); setGraphic(null); return; }
                String kind = a.isMicrosoft() ? "Microsoft" : "offline";
                String marker = a.uuid.equals(app.accounts().defaultAccount() != null
                        ? app.accounts().defaultAccount().uuid : "") ? "  ★ default" : "";
                setText(a.username + "   ·   " + kind + marker);
            }
        });

        Button ms = new Button("Sign in with Microsoft");
        ms.getStyleClass().add("primary");
        ms.setOnAction(e -> microsoftDialog());

        HBox offlineRow = offlineRow();

        Button setDefault = new Button("Set as default");
        setDefault.setOnAction(e -> {
            Account sel = list.getSelectionModel().getSelectedItem();
            if (sel != null) { app.accounts().setDefault(sel); refresh(); }
        });

        Button remove = new Button("Remove");
        remove.getStyleClass().add("danger");
        remove.setOnAction(e -> {
            Account sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Remove account " + sel.username + "?", ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().filter(b -> b == ButtonType.YES).ifPresent(b -> {
                app.accounts().remove(sel);
                refresh();
            });
        });

        HBox buttons = new HBox(10, ms, setDefault, remove);
        Label note = new Label("Microsoft accounts use the official OAuth device flow — your password is never entered in this app.");
        note.getStyleClass().add("muted");
        note.setWrapText(true);

        getChildren().addAll(title, list, buttons, offlineRow, note);
        refresh();
    }

    private HBox offlineRow() {
        TextField name = new TextField();
        name.setPromptText("Username (3-16 chars)");
        name.setPrefWidth(220);
        Button add = new Button("Add offline account");
        add.setOnAction(e -> {
            try {
                Account acc = dev.mclauncher.auth.AccountManager.offline(name.getText().trim());
                app.accounts().add(acc);
                app.accounts().setDefault(acc);
                name.clear();
                refresh();
                app.log("Added offline account " + acc.username);
            } catch (Exception ex) {
                error("Could not add account", ex.getMessage());
            }
        });
        HBox row = new HBox(10, name, add);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 0, 0, 0));
        return row;
    }

    private void microsoftDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Microsoft sign-in");
        Label status = new Label("Requesting device code...");
        Label code = new Label("");
        code.getStyleClass().add("code-box");
        code.setMinHeight(60);
        Hyperlink link = new Hyperlink();
        Button copy = new Button("Copy code");
        Button cancel = new Button("Cancel");
        copy.setDisable(true);

        VBox body = new VBox(12,
                status, code,
                new Label("1. Open the link below (or visit the URL and enter the code)"),
                link,
                new Label("2. Approve the sign-in, then wait here"),
                copy);
        body.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setVisible(false);

        Thread worker = new Thread(() -> {
            try {
                MicrosoftAuth.DeviceCode dc = MicrosoftAuth.requestDeviceCode();
                App.ui(() -> {
                    code.setText(dc.userCode());
                    link.setText(dc.verificationUri());
                    link.setOnAction(e -> app.getHostServices().showDocument(dc.verificationUri()));
                    copy.setDisable(false);
                    copy.setOnAction(e -> {
                        ClipboardContent c = new ClipboardContent();
                        c.putString(dc.userCode());
                        Clipboard.getSystemClipboard().setContent(c);
                    });
                    status.setText("Waiting for authorization...");
                });
                app.getHostServices().showDocument(dc.verificationUri());
                Account acc = MicrosoftAuth.completeDeviceFlow(dc, msg -> {
                    app.log(msg);
                    App.ui(() -> status.setText(msg));
                });
                app.accounts().add(acc);
                app.accounts().setDefault(acc);
                app.log("Signed in as " + acc.username + " (" + acc.uuid + ")");
                App.ui(() -> { refresh(); dialog.close(); });
            } catch (Exception ex) {
                app.log("Microsoft sign-in failed: " + ex.getMessage());
                App.ui(() -> {
                    status.setText("Failed: " + ex.getMessage());
                    dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setVisible(true);
                });
            }
        }, "ms-auth");
        worker.setDaemon(true);
        worker.start();
        dialog.showAndWait();
    }

    private void refresh() {
        list.getItems().setAll(app.accounts().all());
        list.refresh();
    }

    private static void error(String header, String text) {
        Alert a = new Alert(Alert.AlertType.ERROR, text, ButtonType.OK);
        a.setTitle("Error");
        a.setHeaderText(header);
        a.showAndWait();
    }
}
