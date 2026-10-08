package server;

import java.io.IOException;

import server.config.Config;
import server.config.ConfigLoader;

/** Entry point: loads configuration and starts the event-driven server. */
public final class Main {

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "config.json";

        Config config;
        try {
            config = ConfigLoader.load(configPath);
        } catch (IOException e) {
            System.err.println("Failed to read config file '" + configPath + "': " + e.getMessage());
            System.exit(1);
            return;
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid configuration: " + e.getMessage());
            System.exit(1);
            return;
        }

        Server server = new Server(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Server failed to start: " + e.getMessage());
            System.exit(1);
        }
    }
}
