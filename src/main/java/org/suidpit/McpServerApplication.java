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

    public static void startServer(GhidraMCPPlugin plugin) {
        plugins.add(plugin);
        if (context != null && context.isRunning()) {
            return;
        }

        try {
            // Clean up stale port files from dead processes
            cleanStalePortFiles();

            // Resolve port configuration
            PortResolver.PortConfig portConfig = PortResolver.resolvePort();
            int port = PortResolver.findAvailablePort(portConfig.port(), portConfig.isExplicit());
            activePort = port;

            // Start Spring Boot with the resolved port
            // Command-line args have highest priority and will override application.yml
            context = SpringApplication.run(McpServerApplication.class,
                    "--server.port=" + port,
                    "--spring.ai.mcp.server.port=" + port);

            // Write port file and register shutdown hook
            writePortFile(port);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deletePortFile()));

            Msg.info(McpServerApplication.class,
                    "GhidraMCP server started at http://localhost:" + port + "/sse");
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

    /**
     * Writes the active port to a PID-specific file in ~/.ghidra-mcp/
     */
    private static void writePortFile(int port) {
        try {
            Path portDir = Paths.get(System.getProperty("user.home"), ".ghidra-mcp");
            Files.createDirectories(portDir);

            long pid = ProcessHandle.current().pid();
            Path portFile = portDir.resolve("port." + pid);

            Files.writeString(portFile, String.valueOf(port));
            Msg.info(McpServerApplication.class, "Port file: " + portFile);
        } catch (IOException e) {
            Msg.warn(McpServerApplication.class,
                    "Failed to write port file (server will still run): " + e.getMessage());
        }
    }

    /**
     * Cleans up stale port files from dead processes
     */
    private static void cleanStalePortFiles() {
        try {
            Path portDir = Paths.get(System.getProperty("user.home"), ".ghidra-mcp");
            if (!Files.exists(portDir)) {
                return;
            }

            try (Stream<Path> files = Files.list(portDir)) {
                files.filter(path -> path.getFileName().toString().startsWith("port."))
                        .forEach(portFile -> {
                            try {
                                String fileName = portFile.getFileName().toString();
                                long pid = Long.parseLong(fileName.substring("port.".length()));

                                // Check if process is still alive
                                if (ProcessHandle.of(pid).isEmpty()) {
                                    Files.deleteIfExists(portFile);
                                    Msg.info(McpServerApplication.class,
                                            "Cleaned up stale port file: " + portFile);
                                }
                            } catch (NumberFormatException | IOException e) {
                                // Ignore malformed or inaccessible files
                            }
                        });
            }
        } catch (IOException e) {
            Msg.warn(McpServerApplication.class,
                    "Failed to clean stale port files: " + e.getMessage());
        }
    }

    /**
     * Deletes the port file for the current process
     */
    private static void deletePortFile() {
        try {
            long pid = ProcessHandle.current().pid();
            Path portFile = Paths.get(System.getProperty("user.home"), ".ghidra-mcp", "port." + pid);
            Files.deleteIfExists(portFile);
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
            deletePortFile();
            context.close();
            context = null;
            activePort = 0;
        }
    }
}
