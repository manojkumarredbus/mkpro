package com.mkpro;

import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.OllamaBaseLM;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.GoogleSearchTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

public class MkPro {

    // ANSI Color Constants
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_BRIGHT_GREEN = "\u001B[92m";
    public static final String ANSI_YELLOW = "\u001B[33m"; // Closest to Orange
    public static final String ANSI_BLUE = "\u001B[34m";

    public static void main(String[] args) {
        // Check for flags
        boolean useUI = false;
        boolean verbose = false;
        String modelName = "devstral-small-2";

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-ui".equalsIgnoreCase(arg) || "--companion".equalsIgnoreCase(arg)) {
                useUI = true;
            } else if ("-v".equalsIgnoreCase(arg) || "--verbose".equalsIgnoreCase(arg)) {
                verbose = true;
            } else if ("-m".equalsIgnoreCase(arg) || "--model".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    modelName = args[i + 1];
                    i++; // Skip next arg
                }
            }
        }

        final boolean isVerbose = verbose;

        if (isVerbose) {
            System.out.println(ANSI_BLUE + "Initializing mkpro assistant with model: " + modelName + ANSI_RESET);
            Logger root = (Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.DEBUG);
        } else {
             // Ensure it stays at WARN (or whatever logback.xml said) unless we want to force it.
             // But logback.xml handles the default.
             // We can optionally force OFF if we want it *really* quiet.
             // For now, let's respect logback.xml (WARN).
        }

        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println(ANSI_BLUE + "Error: GOOGLE_API_KEY environment variable not set." + ANSI_RESET);
            System.exit(1);
        }

        InMemorySessionService sessionService = new InMemorySessionService();
        InMemoryArtifactService artifactService = new InMemoryArtifactService();
        InMemoryMemoryService memoryService = new InMemoryMemoryService();

        // Define File Tool
        BaseTool readFileTool = new BaseTool(
                "read_file",
                "Reads the content of a file from the local filesystem."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "file_path", Schema.builder() 
                                                .type("STRING")
                                                .description("The path to the file to read.")
                                                .build()
                                ))
                                .required(ImmutableList.of("file_path"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                if (isVerbose) System.out.println(ANSI_BLUE + "[DEBUG] Tool invoked: read_file" + ANSI_RESET);
                String filePath = (String) args.get("file_path");
                try {
                    Path path = Paths.get(filePath);
                    if (!Files.exists(path)) {
                         return Single.just(Collections.singletonMap("error", "File not found: " + filePath));
                    }
                    String content = Files.readString(path);
                    if (content.length() > 10000) {
                        content = content.substring(0, 10000) + "\n...[truncated]";
                    }
                    return Single.just(Collections.singletonMap("content", content));
                } catch (IOException e) {
                    return Single.just(Collections.singletonMap("error", "Error reading file: " + e.getMessage()));
                }
            }
        };
        
        // Define List Directory Tool
        BaseTool listDirTool = new BaseTool(
                "list_directory",
                "Lists the files and directories in a given path."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "dir_path", Schema.builder()
                                                .type("STRING")
                                                .description("The path to the directory to list.")
                                                .build()
                                ))
                                .required(ImmutableList.of("dir_path"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                if (isVerbose) System.out.println(ANSI_BLUE + "[DEBUG] Tool invoked: list_directory" + ANSI_RESET);
                String dirPath = (String) args.get("dir_path");
                try {
                     Path path = Paths.get(dirPath);
                    if (!Files.exists(path) || !Files.isDirectory(path)) {
                         return Single.just(Collections.singletonMap("error", "Directory not found: " + dirPath));
                    }
                    
                    StringBuilder listing = new StringBuilder();
                    Files.list(path).forEach(p -> {
                        listing.append(p.getFileName().toString());
                        if (Files.isDirectory(p)) {
                            listing.append("/");
                        }
                        listing.append("\n");
                    });
                    
                    return Single.just(Collections.singletonMap("listing", listing.toString()));
                } catch (IOException e) {
                    return Single.just(Collections.singletonMap("error", "Error listing directory: " + e.getMessage()));
                }
            }
        };

        // Define URL Fetch Tool
        BaseTool urlFetchTool = new BaseTool(
                "fetch_url",
                "Fetches and extracts text content from a given URL."
        ) {
            private final HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "url", Schema.builder()
                                                .type("STRING")
                                                .description("The full URL to fetch content from.")
                                                .build()
                                ))
                                .required(ImmutableList.of("url"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                if (isVerbose) System.out.println(ANSI_BLUE + "[DEBUG] Tool invoked: fetch_url" + ANSI_RESET);
                String url = (String) args.get("url");
                return Single.fromCallable(() -> {
                    try {
                        if (isVerbose) System.out.println(ANSI_BLUE + "[DEBUG] Fetching URL: " + url + ANSI_RESET);
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .timeout(Duration.ofSeconds(20))
                                .GET()
                                .build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        
                        if (response.statusCode() >= 400) {
                            return Collections.singletonMap("error", "HTTP Error: " + response.statusCode());
                        }

                        String html = response.body();
                        // Basic stripping
                        String text = html.replaceAll("(?s)<style.*?>.*?</style>", "")
                                          .replaceAll("(?s)<script.*?>.*?</script>", "")
                                          .replaceAll("<[^>]+>", " ")
                                          .replaceAll("\\s+", " ")
                                          .trim();
                        
                        if (text.length() > 20000) {
                            text = text.substring(0, 20000) + "\n...[truncated]";
                        }
                        
                        return Collections.singletonMap("content", text);
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Failed to fetch URL: " + e.getMessage());
                    }
                });
            }
        };

        // Define Read Image Tool
        BaseTool readImageTool = new BaseTool(
                "read_image",
                "Reads an image file and returns its Base64 encoded content. Use this to analyze images."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "file_path", Schema.builder()
                                                .type("STRING")
                                                .description("The path to the image file.")
                                                .build()
                                ))
                                .required(ImmutableList.of("file_path"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                if (isVerbose) System.out.println(ANSI_BLUE + "[DEBUG] Tool invoked: read_image" + ANSI_RESET);
                String filePath = (String) args.get("file_path");
                return Single.fromCallable(() -> {
                    try {
                        Path path = Paths.get(filePath);
                        if (!Files.exists(path)) {
                            return Collections.singletonMap("error", "File not found: " + filePath);
                        }
                        byte[] bytes = Files.readAllBytes(path);
                        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                        String mimeType = "image/jpeg";
                        if (filePath.toLowerCase().endsWith(".png")) mimeType = "image/png";
                        else if (filePath.toLowerCase().endsWith(".webp")) mimeType = "image/webp";
                        
                        return ImmutableMap.of(
                            "mime_type", mimeType,
                            "data", base64
                        );
                    } catch (IOException e) {
                        return Collections.singletonMap("error", "Error reading image: " + e.getMessage());
                    }
                });
            }
        };

        // Define Write File Tool
        BaseTool writeFileTool = new BaseTool(
                "write_file",
                "Writes content to a file, overwriting it."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                 return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "file_path", Schema.builder()
                                                .type("STRING")
                                                .description("The path to the file.")
                                                .build(),
                                        "content", Schema.builder()
                                                .type("STRING")
                                                .description("The content to write.")
                                                .build()
                                ))
                                .required(ImmutableList.of("file_path", "content"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                if (isVerbose) System.out.println(ANSI_BLUE + "[DEBUG] Tool invoked: write_file" + ANSI_RESET);
                String filePath = (String) args.get("file_path");
                String content = (String) args.get("content");
                return Single.fromCallable(() -> {
                    try {
                        Path path = Paths.get(filePath);
                        if (path.getParent() != null) {
                            Files.createDirectories(path.getParent());
                        }
                        Files.writeString(path, content, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                        return Collections.singletonMap("status", "File written successfully: " + filePath);
                    } catch (IOException e) {
                        return Collections.singletonMap("error", "Error writing file: " + e.getMessage());
                    }
                });
            }
        };

        // Define Run Shell Tool
        BaseTool runShellTool = new BaseTool(
                "run_shell",
                "Executes a shell command."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "command", Schema.builder()
                                                .type("STRING")
                                                .description("The command to execute.")
                                                .build()
                                ))
                                .required(ImmutableList.of("command"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                if (isVerbose) System.out.println(ANSI_BLUE + "[DEBUG] Tool invoked: run_shell" + ANSI_RESET);
                String command = (String) args.get("command");
                return Single.fromCallable(() -> {
                    try {
                        ProcessBuilder pb;
                        String os = System.getProperty("os.name").toLowerCase();
                        if (os.contains("win")) {
                            pb = new ProcessBuilder("cmd.exe", "/c", command);
                        } else {
                            pb = new ProcessBuilder("sh", "-c", command);
                        }
                        pb.redirectErrorStream(true);
                        Process process = pb.start();
                        
                        String output = new String(process.getInputStream().readAllBytes());
                        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
                        if (!exited) {
                             process.destroy();
                             output += "\n[Timeout]";
                        }
                        int exitCode = exited ? process.exitValue() : -1;

                        return ImmutableMap.of(
                            "exit_code", exitCode,
                            "output", output
                        );
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Command failed: " + e.getMessage());
                    }
                });
            }
        };

        Session session = sessionService.createSession("mkpro-cli", "user").blockingGet();

        ActionLogger logger = new ActionLogger("mkpro_logs.db");

        // Define Get Action Logs Tool
        BaseTool getActionLogsTool = new BaseTool(
                "get_action_logs",
                "Retrieves the history of user actions and agent responses."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Collections.emptyMap()) // No parameters needed
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                if (isVerbose) System.out.println(ANSI_BLUE + "[DEBUG] Tool invoked: get_action_logs" + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        StringBuilder logsBuilder = new StringBuilder();
                        for (String log : logger.getLogs()) {
                            logsBuilder.append(log).append("\n");
                        }
                        return Collections.singletonMap("logs", logsBuilder.toString());
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Error retrieving logs: " + e.getMessage());
                    }
                });
            }
        };

        LlmAgent agent = LlmAgent.builder()
                .name("mkpro")
                .description("A helpful coding and research assistant.")
                .instruction("You are mkpro, a powerful coding assistant. "
                        + "You have access to the local filesystem AND the internet. "
                        + "You can also ANALYZE IMAGES using the 'read_image' tool. "
                        + "TOOLS AVAILABLE:\n"
                        + "- read_file: Read local files (text).\n"
                        + "- read_image: Read image files (returns base64).\n"
                        + "- write_file: Create or overwrite files.\n"
                        + "- run_shell: Execute shell commands (git, etc).\n"
                        + "- list_directory: List files in folders.\n"
                        + "- fetch_url: Access external websites to read documentation. USE THIS when given a URL.\n"
                        + "- google_search: Search Google for general information.\n"
                        + "- get_action_logs: Retrieve history of interactions.\n\n"
                        + "IMPORTANT: Before using write_file to modify code, you MUST use run_shell to 'git add .' and 'git commit' to save the current state.\n"
                        + "Always prefer concise answers.")
                //.model("gemini-2.0-flash-exp")
                .model(new OllamaBaseLM(modelName, "http://localhost:11434"))
                .tools(readFileTool, readImageTool, writeFileTool, runShellTool, listDirTool, urlFetchTool, getActionLogsTool)//, GoogleSearchTool.INSTANCE
                .planning(true)
                .build();

        Runner runner = Runner.builder()
                .agent(agent)
                .appName("mkpro-cli")
                .sessionService(sessionService)
                .artifactService(artifactService)
                .memoryService(memoryService)
                .build();

        if (useUI) {
            if (isVerbose) System.out.println(ANSI_BLUE + "Launching Swing Companion UI..." + ANSI_RESET);
            SwingCompanion gui = new SwingCompanion(runner, session, sessionService);
            gui.show();
        } else {
            runConsoleLoop(runner, session, logger, isVerbose);
        }
        
        logger.close();
    }

    private static void runConsoleLoop(Runner runner, Session session, ActionLogger logger, boolean verbose) {
        if (verbose) {
            System.out.println(ANSI_BLUE + "mkpro ready! Type 'exit' to quit." + ANSI_RESET);
        }
        System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW); // Prompt Blue, Input Yellow

        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            System.out.print(ANSI_RESET); // Reset after input
            
            if ("exit".equalsIgnoreCase(line.trim())) {
                break;
            }

            logger.log("USER", line);

            Content content = Content.builder()
                    .role("user")
                    .parts(Collections.singletonList(Part.builder().text(line).build()))
                    .build();

            // Start Spinner
            AtomicBoolean isThinking = new AtomicBoolean(true);
            Thread spinnerThread = new Thread(() -> {
                String[] syms = {"|", "/", "-", "\\"};
                int i = 0;
                while (isThinking.get()) {
                    System.out.print("\r" + ANSI_BLUE + "Thinking " + syms[i++ % syms.length] + ANSI_RESET);
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                }
                // Clear spinner line
                System.out.print("\r" + " ".repeat(20) + "\r"); 
            });
            spinnerThread.start();

            try {
                StringBuilder responseBuilder = new StringBuilder();
                
                runner.runAsync("user", session.id(), content)
                        .filter(event -> event.content().isPresent())
                        .blockingForEach(event -> {
                            if (isThinking.getAndSet(false)) {
                                // Stop spinner logic
                                spinnerThread.interrupt(); 
                                try { spinnerThread.join(); } catch (InterruptedException ignored) {}
                                System.out.print("\r" + " ".repeat(20) + "\r"); // Ensure clear
                                
                                System.out.print(ANSI_BRIGHT_GREEN); // Start Agent Bright Green
                            }
                            
                            event.content()
                                .flatMap(Content::parts)
                                .orElse(Collections.emptyList())
                                .forEach(part -> 
                                    part.text().ifPresent(text -> {
                                        System.out.print(text);
                                        responseBuilder.append(text);
                                    })
                                );
                        });
                
                // Handle case where no content was returned (thinking still true)
                if (isThinking.getAndSet(false)) {
                     spinnerThread.interrupt();
                     try { spinnerThread.join(); } catch (InterruptedException ignored) {}
                     System.out.print("\r" + " ".repeat(20) + "\r");
                }

                System.out.println(ANSI_RESET); // End Agent Green
                logger.log("AGENT", responseBuilder.toString());
            } catch (Exception e) {
                // Ensure spinner stops on error
                if (isThinking.getAndSet(false)) {
                     spinnerThread.interrupt();
                     try { spinnerThread.join(); } catch (InterruptedException ignored) {}
                     System.out.print("\r" + " ".repeat(20) + "\r");
                }
                
                System.err.println(ANSI_BLUE + "Error processing request: " + e.getMessage() + ANSI_RESET);
                if (verbose) {
                    e.printStackTrace();
                }
                logger.log("ERROR", e.getMessage());
            }

            System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW); // Prompt Blue, Input Yellow
        }
        
        if (verbose) System.out.println(ANSI_BLUE + "Goodbye!" + ANSI_RESET);
    }
}