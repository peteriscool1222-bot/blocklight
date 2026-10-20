package dev.mclauncher;

import javafx.application.Application;

/**
 * Entry point. Kept separate from the Application subclass so the fat-jar
 * classpath launch works (JavaFX complains if the *main* class extends Application).
 */
public final class MainLauncher {
    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
