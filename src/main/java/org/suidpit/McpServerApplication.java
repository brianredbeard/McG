package org.suidpit;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import ghidra.program.model.listing.Program;
import ghidra.util.Msg;


@SpringBootApplication
public class McpServerApplication {
    private static ConfigurableApplicationContext context;
    private static final List<GhidraMCPPlugin> plugins = new CopyOnWriteArrayList<>();
    private static volatile GhidraMCPPlugin selectedPlugin;
    private static volatile int activePort = 0;
    private static volatile String activeHost = "127.0.0.1";
    private static final Path MCP_DIR = Paths.get(System.getProperty("user.home"), ".ghidra-mcp");

    public static void startServer(GhidraMCPPlugin plugin) {
        plugins.add(plugin);
        if (context != null && context.isRunning()) {
            return;
        }

        try {
            // Clean up stale config files
            cleanStalePortFiles();

            // Resolve host and port configuration
            String host = PortResolver.resolveHost();
            PortResolver.PortConfig portConfig = PortResolver.resolvePort();
            int port = PortResolver.findAvailablePort(portConfig.port(), portConfig.isExplicit());
            activeHost = host;
            activePort = port;

            // Warn if binding to non-localhost
            if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                Msg.warn(McpServerApplication.class,
                        "Warning: MCP server is accessible from the network (" + host + "). " +
                        "No authentication is configured.");
            }

            // Start Spring Boot with the resolved host and port
            // Command-line args have highest priority and will override application.yml
            context = SpringApplication.run(McpServerApplication.class,
                    "--server.address=" + host,
                    "--server.port=" + port,
                    "--spring.ai.mcp.server.port=" + port);

            // Write config files and register shutdown hook
            writeConfigFiles(host, port);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteConfigFiles()));

            // Use localhost in URLs for readability when bound to 127.0.0.1
            String displayHost = "127.0.0.1".equals(host) ? "localhost" : host;
            Msg.info(McpServerApplication.class,
                    "McG server started at http://" + displayHost + ":" + port + "/sse");
        } catch (RuntimeException e) {
            // Handle port resolution failures and TOCTOU race conditions
            if (e instanceof PortInUseException || e.getCause() instanceof BindException) {
                Msg.error(McpServerApplication.class,
                        "Port " + activePort + " was available during pre-check but is now in use by another process. " +
                                "This is a timing race condition. Try restarting Ghidra, or set GHIDRA_MCP_PORT " +
                                "environment variable or -Dghidra.mcp.port system property to specify a different port.");
            } else {
                Msg.error(McpServerApplication.class,
                        "Failed to start GhidraMCP server: " + e.getMessage(), e);
            }
            activePort = 0;
            context = null;
        }
    }

    /**
     * Returns the active port number, or 0 if the server is not running.
     */
    public static int getPort() {
        return activePort;
    }

    private static final Path MCP_DIR = Paths.get(System.getProperty("user.home"), ".ghidra-mcp");

    /**
     * Writes MCP client config files to ~/.ghidra-mcp/
     *
     * Generated files:
     *   mcp.json         - latest instance config (symlink target for projects)
     *   mcp.<port>.json  - per-instance config keyed by port
     */
    private static void writeConfigFiles(String host, int port) {
        try {
            Files.createDirectories(MCP_DIR);

            // Use localhost for readability when bound to 127.0.0.1
            String urlHost = "127.0.0.1".equals(host) ? "localhost" : host;

            String mcpConfig = "{\n" +
                    "  \"mcpServers\": {\n" +
                    "    \"McG\": {\n" +
                    "      \"type\": \"sse\",\n" +
                    "      \"url\": \"http://" + urlHost + ":" + port + "/sse\"\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n";


            Files.writeString(MCP_DIR.resolve("mcp." + port + ".json"), mcpConfig);
            Files.writeString(MCP_DIR.resolve("mcp.json"), mcpConfig);

            Msg.info(McpServerApplication.class,
                    "MCP config: " + MCP_DIR.resolve("mcp.json") +
                    " (symlink this into your project as .mcp.json)");
        } catch (IOException e) {
            Msg.warn(McpServerApplication.class,
                    "Failed to write config files (server will still run): " + e.getMessage());
        }
    }

    /**
     * Cleans up stale config files by checking if the port is still in use.
     * If the port is available (no server listening), the config file is stale.
     */
    private static void cleanStalePortFiles() {
        try {
            if (!Files.exists(MCP_DIR)) {
                return;
            }

            try (Stream<Path> files = Files.list(MCP_DIR)) {
                files.filter(path -> {
                            String name = path.getFileName().toString();
                            return name.startsWith("mcp.") && name.endsWith(".json") && !name.equals("mcp.json");
                        })
                        .forEach(file -> {
                            try {
                                String fileName = file.getFileName().toString();
                                int port = Integer.parseInt(
                                        fileName.substring("mcp.".length(), fileName.length() - ".json".length()));

                                // If the port is available, no server is listening — file is stale
                                if (PortResolver.isPortAvailable(port)) {
                                    Files.deleteIfExists(file);
                                    Msg.info(McpServerApplication.class,
                                            "Cleaned up stale config: " + file);
                                }
                            } catch (NumberFormatException | IOException e) {
                                // Ignore malformed or inaccessible files
                            }
                        });
            }
        } catch (IOException e) {
            Msg.warn(McpServerApplication.class,
                    "Failed to clean stale config files: " + e.getMessage());
        }
    }

    /**
     * Deletes config files for the current server instance
     */
    private static void deleteConfigFiles() {
        try {
            Files.deleteIfExists(MCP_DIR.resolve("mcp." + activePort + ".json"));
            // Only remove mcp.json if we're the last instance
            if (plugins.isEmpty()) {
                Files.deleteIfExists(MCP_DIR.resolve("mcp.json"));
            }
        } catch (IOException e) {
            // Ignore cleanup errors
        }
    }

    public static void removePlugin(GhidraMCPPlugin plugin) {
        plugins.remove(plugin);
        if (selectedPlugin == plugin) {
            selectedPlugin = null;
        }

        // If this was the last plugin, stop the server and clean up
        if (plugins.isEmpty()) {
            stopServer();
        }
    }

    /**
     * Returns the explicitly selected plugin, or the first registered plugin
     * that has an active program open, or the first registered plugin if none
     * have a program.
     */
    static GhidraMCPPlugin getActivePlugin() {
        if (selectedPlugin != null && selectedPlugin.getCurrentProgram() != null) {
            return selectedPlugin;
        }
        for (GhidraMCPPlugin p : plugins) {
            if (p.getCurrentProgram() != null) {
                return p;
            }
        }
        return plugins.isEmpty() ? null : plugins.get(0);
    }

    /**
     * Returns info about all open programs across all plugin instances.
     */
    static List<String> getOpenPrograms() {
        var result = new ArrayList<String>();
        for (GhidraMCPPlugin p : plugins) {
            Program prog = p.getCurrentProgram();
            if (prog != null) {
                String marker = (p == getActivePlugin()) ? " [active]" : "";
                result.add(prog.getName() + " (" + prog.getLanguageID() + ")" + marker);
            }
        }
        return result;
    }

    /**
     * Select a specific program by name. Returns true if found.
     */
    static boolean selectProgram(String programName) {
        for (GhidraMCPPlugin p : plugins) {
            Program prog = p.getCurrentProgram();
            if (prog != null && prog.getName().equals(programName)) {
                selectedPlugin = p;
                return true;
            }
        }
        return false;
    }

    @Bean
    public ToolCallbackProvider ghidraTools(GhidraService ghidraService) {
        return MethodToolCallbackProvider.builder().toolObjects(ghidraService).build();
    }

    public static void stopServer() {
        if (context != null) {
            deleteConfigFiles();
            context.close();
            context = null;
            activePort = 0;
            activeHost = "127.0.0.1";
        }
    }
}
