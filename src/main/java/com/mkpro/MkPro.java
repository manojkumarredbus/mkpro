package com.mkpro;

import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.OllamaBaseLM;
import com.google.adk.models.Gemini;
import com.google.adk.models.BaseLlm;
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
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

public class MkPro {

    public enum Provider {
        OLLAMA,
        GEMINI
    }

    // ANSI Color Constants
    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BRIGHT_GREEN = "\u001b[92m";
    public static final String ANSI_YELLOW = "\u001b[33m"; // Closest to Orange
    public static final String ANSI_BLUE = "\u001b[34m";

    public static void main(String[] args) {
        // Check for flags
        boolean useUI = false;
        boolean verbose = false;
        String initialModelName = "devstral-small-2";

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-ui".equalsIgnoreCase(arg) || "--companion".equalsIgnoreCase(arg)) {
                useUI = true;
            } else if ("-v".equalsIgnoreCase(arg) || "--verbose".equalsIgnoreCase(arg)) {
                verbose = true;
            } else if ("-m".equalsIgnoreCase(arg) || "--model".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    initialModelName = args[i + 1];
                    i++; // Skip next arg
                }
            }
        }
        
        final String modelName = initialModelName;
        final boolean isVerbose = verbose;

        if (isVerbose) {
            System.out.println(ANSI_BLUE + "Initializing mkpro assistant with model: " + modelName + ANSI_RESET);
            Logger root = (Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.DEBUG);
        }

        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println(ANSI_BLUE + "Error: GOOGLE_API_KEY environment variable not set." + ANSI_RESET);
            System.exit(1);
        }

        // Load previous session summary if available
        String summaryContext = "";
        try {
            Path summaryPath = Paths.get("session_summary.txt");
            if (Files.exists(summaryPath)) {
                if (isVerbose) System.out.println(ANSI_BLUE + "Loading previous session summary..." + ANSI_RESET);
                summaryContext = "\n\nPREVIOUS SESSION CONTEXT:\n" + Files.readString(summaryPath);
            }
        } catch (IOException e) {
            System.err.println(ANSI_BLUE + "Warning: Could not read session_summary.txt" + ANSI_RESET);
        }
        
        final String finalSummaryContext = summaryContext;

        InMemorySessionService sessionService = new InMemorySessionService();
        InMemoryArtifactService artifactService = new InMemoryArtifactService();
        InMemoryMemoryService memoryService = new InMemoryMemoryService();
        
        CentralMemory centralMemory = new CentralMemory();
        Session mkSession = sessionService.createSession("mkpro-cli", "user").blockingGet();
        ActionLogger logger = new ActionLogger("mkpro_logs.db");

        // --- DEFINE TOOLS ---

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
                String filePath = (String) args.get("file_path");
                System.out.println(ANSI_BLUE + "[Coder] Reading file: " + filePath + ANSI_RESET);
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
                String dirPath = (String) args.get("dir_path");
                System.out.println(ANSI_BLUE + "[Coder] Listing directory: " + dirPath + ANSI_RESET);
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
                String url = (String) args.get("url");
                System.out.println(ANSI_BLUE + "[Coordinator] Fetching URL: " + url + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
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
                String filePath = (String) args.get("file_path");
                System.out.println(ANSI_BLUE + "[Coder] Analyzing image: " + filePath + ANSI_RESET);
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
                String filePath = (String) args.get("file_path");
                System.out.println(ANSI_BLUE + "[Coder] Writing file: " + filePath + ANSI_RESET);
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
                String command = (String) args.get("command");
                System.out.println(ANSI_BLUE + "[SysAdmin] Executing: " + command + ANSI_RESET);
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

        BaseTool saveMemoryTool = new BaseTool(
                "save_central_memory",
                "Saves a summary or memory of the current project to the user's central database."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "content", Schema.builder()
                                                .type("STRING")
                                                .description("The summary content to save.")
                                                .build()
                                ))
                                .required(ImmutableList.of("content"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String content = (String) args.get("content");
                String currentPath = Paths.get("").toAbsolutePath().toString();
                System.out.println(ANSI_BLUE + "[Coordinator] Saving to central memory for: " + currentPath + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        centralMemory.saveMemory(currentPath, content);
                        return Collections.singletonMap("status", "Memory saved successfully for " + currentPath);
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Error saving memory: " + e.getMessage());
                    }
                });
            }
        };

        BaseTool readMemoryTool = new BaseTool(
                "read_central_memory",
                "Reads the stored memory for a project. If no path is provided, reads the current project's memory."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "project_path", Schema.builder()
                                                .type("STRING")
                                                .description("Optional absolute path to the project. Defaults to current directory.")
                                                .build()
                                ))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String pathArg = (String) args.get("project_path");
                String currentPath = (pathArg != null && !pathArg.isBlank()) ? pathArg : Paths.get("").toAbsolutePath().toString();
                
                System.out.println(ANSI_BLUE + "[Coordinator] Reading central memory for: " + currentPath + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        String memory = centralMemory.getMemory(currentPath);
                        if (memory == null) {
                            return Collections.singletonMap("memory", "No memory found for this project.");
                        }
                        return Collections.singletonMap("memory", memory);
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Error reading memory: " + e.getMessage());
                    }
                });
            }
        };

        BaseTool listProjectsTool = new BaseTool(
                "list_central_memory_projects",
                "Lists all project paths that have data stored in the central memory database."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Collections.emptyMap())
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                System.out.println(ANSI_BLUE + "[Coordinator] Listing central memory projects..." + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        Map<String, String> memories = centralMemory.getAllMemories();
                        if (memories.isEmpty()) {
                             return Collections.singletonMap("projects", "No projects found in central memory.");
                        }
                        return Collections.singletonMap("projects", String.join("\n", memories.keySet()));
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Error listing projects: " + e.getMessage());
                    }
                });
            }
        };

        // --- SUB-AGENT RUNNER HELPER ---
        
        Function<AgentRequest, String> subAgentRunner = (request) -> {
            try {
                // Reuse sessionService but create new session for sub-task to keep context clean
                Session subSession = sessionService.createSession(request.agentName, "user").blockingGet();
                
                BaseLlm model;
                if (request.provider == Provider.GEMINI) {
                    model = new Gemini(request.modelName, apiKey);
                } else {
                    model = new OllamaBaseLM(request.modelName, "http://localhost:11434");
                }
                
                LlmAgent subAgent = LlmAgent.builder()
                    .name(request.agentName)
                    .instruction(request.instruction)
                    .model(model)
                    .tools(request.tools)
                    .planning(true) // Enable planning for sub-agents too
                    .build();

                Runner subRunner = Runner.builder()
                    .agent(subAgent)
                    .appName(request.agentName) // Match Session app name
                    .sessionService(sessionService)
                    .artifactService(artifactService)
                    .memoryService(memoryService)
                    .build();

                StringBuilder output = new StringBuilder();
                Content content = Content.builder().role("user").parts(List.of(Part.fromText(request.userPrompt))).build();
                
                subRunner.runAsync("user", subSession.id(), content)
                      .filter(e -> e.content().isPresent())
                      .blockingForEach(e -> 
                          e.content().flatMap(Content::parts).orElse(Collections.emptyList())
                           .forEach(p -> p.text().ifPresent(output::append))
                      );
                return output.toString();
            } catch (Exception e) {
                return "Error executing sub-agent " + request.agentName + ": " + e.getMessage();
            }
        };

        // --- DELEGATION TOOLS ---

        // We will move tool creation into the factory to capture 'currentModelName'

        // Factory to create Runner with specific model
        BiFunction<String, Provider, Runner> runnerFactory = (currentModelName, currentProvider) -> {
            
            String contextInfo = "\nCurrent Date: " + LocalDate.now() + "\nCurrent Working Directory: " + Paths.get("").toAbsolutePath().toString();

            // Model selection logic
            BaseLlm model;
            if (currentProvider == Provider.GEMINI) {
                model = new Gemini(currentModelName, apiKey);
            } else {
                model = new OllamaBaseLM(currentModelName, "http://localhost:11434");
            }

            // --- Re-define Delegation Tools to capture currentModelName ---
            
            BaseTool scopedAskCoderTool = new BaseTool(
                "ask_coder",
                "Delegates coding tasks to the Coder agent (read/write files, list dirs)."
            ) {
                @Override
                public Optional<FunctionDeclaration> declaration() {
                    return Optional.of(FunctionDeclaration.builder()
                            .name(name())
                            .description(description())
                            .parameters(Schema.builder()
                                    .type("OBJECT")
                                    .properties(ImmutableMap.of(
                                            "instruction", Schema.builder().type("STRING").description("Instructions for Coder.").build()
                                    ))
                                    .required(ImmutableList.of("instruction"))
                                    .build())
                            .build());
                }

                @Override
                public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                    String instruction = (String) args.get("instruction");
                    System.out.println(ANSI_BLUE + ">> Delegating to Coder..." + ANSI_RESET);
                    return Single.fromCallable(() -> {
                        String result = subAgentRunner.apply(new AgentRequest(
                            "Coder", 
                            "You are the Coder. You specialize in software engineering. " +
                            "You can read files, write files, list directories, and analyze images. " +
                            "You CANNOT execute shell commands directly. " +
                            "Perform the requested task and provide a concise report." +
                            contextInfo,
                            currentModelName,
                            currentProvider,
                            instruction,
                            List.of(readFileTool, writeFileTool, listDirTool, readImageTool)
                        ));
                        return Collections.singletonMap("result", result);
                    });
                }
            };

            BaseTool scopedAskSysAdminTool = new BaseTool(
                "ask_sysadmin",
                "Delegates system command execution to the SysAdmin agent (shell commands)."
            ) {
                @Override
                public Optional<FunctionDeclaration> declaration() {
                    return Optional.of(FunctionDeclaration.builder()
                            .name(name())
                            .description(description())
                            .parameters(Schema.builder()
                                    .type("OBJECT")
                                    .properties(ImmutableMap.of(
                                            "instruction", Schema.builder().type("STRING").description("Instructions for SysAdmin.").build()
                                    ))
                                    .required(ImmutableList.of("instruction"))
                                    .build())
                            .build());
                }

                @Override
                public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                    String instruction = (String) args.get("instruction");
                    System.out.println(ANSI_BLUE + ">> Delegating to SysAdmin..." + ANSI_RESET);
                    return Single.fromCallable(() -> {
                        String result = subAgentRunner.apply(new AgentRequest(
                            "SysAdmin", 
                            "You are the System Administrator. " +
                            "You specialize in executing shell commands safely. " +
                            "You can use 'run_shell'. Execute the requested commands and report the output." +
                            contextInfo,
                            currentModelName,
                            currentProvider,
                            instruction,
                            List.of(runShellTool)
                        ));
                        return Collections.singletonMap("result", result);
                    });
                }
            };

            // Coordinator Tools
            List<BaseTool> coordinatorTools = new ArrayList<>();
            coordinatorTools.add(scopedAskCoderTool);
            coordinatorTools.add(scopedAskSysAdminTool);
            coordinatorTools.add(urlFetchTool);
            coordinatorTools.add(getActionLogsTool);
            coordinatorTools.add(saveMemoryTool);
            coordinatorTools.add(readMemoryTool);
            coordinatorTools.add(listProjectsTool);
            coordinatorTools.add(listDirTool); 

            LlmAgent coordinatorAgent = LlmAgent.builder()
                .name("Coordinator")
                .description("The main orchestrator agent.")
                .instruction("You are the Coordinator. You interface with the user and manage the workflow. "
                        + "You have two specialized sub-agents: \n" 
                        + "1. **Coder**: Handles all file operations (read, write, analyze images). \n" 
                        + "2. **SysAdmin**: Handles all shell command executions. \n" 
                        + "Delegate tasks appropriately. Do not try to write files or run commands yourself; you don't have those tools. " 
                        + "You DO have tools to fetch URLs and manage long-term memory. " 
                        + "Always prefer concise answers."
                        + contextInfo
                        + finalSummaryContext)
                .model(model)
                .tools(coordinatorTools)
                .planning(true)
                .build();

            return Runner.builder()
                .agent(coordinatorAgent)
                .appName("mkpro-cli") // MATCHING APP NAME
                .sessionService(sessionService)
                .artifactService(artifactService)
                .memoryService(memoryService)
                .build();
        };

        if (useUI) {
            if (isVerbose) System.out.println(ANSI_BLUE + "Launching Swing Companion UI..." + ANSI_RESET);
            // UI uses initial model, defaults to OLLAMA for now unless arg changes (TODO: add arg for provider)
            Runner runner = runnerFactory.apply(modelName, Provider.OLLAMA);
            SwingCompanion gui = new SwingCompanion(runner, mkSession, sessionService);
            gui.show();
        } else {
            // Default provider OLLAMA
            runConsoleLoop(runnerFactory, modelName, Provider.OLLAMA, mkSession, sessionService, centralMemory, logger, isVerbose, apiKey);
        }
        
        logger.close();
        // centralMemory.close(); // removed as it is not Closeable
    }

    // Helper class to pass data to subAgentRunner
    private static class AgentRequest {
        String agentName;
        String instruction;
        String modelName;
        Provider provider;
        String userPrompt;
        List<BaseTool> tools;

        public AgentRequest(String agentName, String instruction, String modelName, Provider provider, String userPrompt, List<BaseTool> tools) {
            this.agentName = agentName;
            this.instruction = instruction;
            this.modelName = modelName;
            this.provider = provider;
            this.userPrompt = userPrompt;
            this.tools = tools;
        }
    }

    private static final List<String> GEMINI_MODELS = Arrays.asList(
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
        "gemini-1.5-flash",
        "gemini-1.5-flash-8b",
        "gemini-1.5-pro",
        "gemini-2.0-flash-exp"
    );

    private static void runConsoleLoop(BiFunction<String, Provider, Runner> runnerFactory, String initialModelName, Provider initialProvider, Session initialSession, InMemorySessionService sessionService, CentralMemory centralMemory, ActionLogger logger, boolean verbose, String apiKey) {
        String currentModelName = initialModelName;
        Provider currentProvider = initialProvider;
        Runner runner = runnerFactory.apply(currentModelName, currentProvider);
        Session currentSession = initialSession;
        
        if (verbose) {
            System.out.println(ANSI_BLUE + "mkpro ready! Type 'exit' to quit." + ANSI_RESET);
        }
        System.out.println(ANSI_BLUE + "Type '/help' for a list of commands." + ANSI_RESET);
        System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW); // Prompt Blue, Input Yellow

        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            System.out.print(ANSI_RESET); // Reset after input
            
            if ("exit".equalsIgnoreCase(line.trim())) {
                break;
            }

            if ("/h".equalsIgnoreCase(line.trim()) || "/help".equalsIgnoreCase(line.trim())) {
                System.out.println(ANSI_BLUE + "Available Commands:" + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /provider   - Switch between OLLAMA and GEMINI providers." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /models     - List available models (for current provider if supported)." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /model      - Change the current model." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /init       - Initialize project memory (if not exists)." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /re-init    - Re-initialize/Update project memory." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /remember   - Analyze project and save summary to central memory." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /reset      - Reset the session (clears memory)." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /compact    - Compact the session (summarize history and start fresh)." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /summarize  - Generate a summary of the session to 'session_summary.txt'." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  exit        - Quit the application." + ANSI_RESET);
                System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                continue;
            }

            if ("/provider".equalsIgnoreCase(line.trim())) {
                System.out.println(ANSI_BLUE + "Current Provider: " + currentProvider + ANSI_RESET);
                System.out.println(ANSI_BLUE + "Select new provider:" + ANSI_RESET);
                System.out.println(ANSI_BRIGHT_GREEN + "[1] OLLAMA" + ANSI_RESET);
                System.out.println(ANSI_BRIGHT_GREEN + "[2] GEMINI" + ANSI_RESET);
                System.out.print(ANSI_BLUE + "Enter selection: " + ANSI_YELLOW);
                String selection = scanner.nextLine().trim();
                System.out.print(ANSI_RESET);
                
                if ("1".equals(selection)) {
                    currentProvider = Provider.OLLAMA;
                    System.out.println(ANSI_BLUE + "Provider switched to OLLAMA. Please use /model to select an Ollama model if needed." + ANSI_RESET);
                } else if ("2".equals(selection)) {
                    currentProvider = Provider.GEMINI;
                    System.out.println(ANSI_BLUE + "Provider switched to GEMINI. Defaulting to 'gemini-1.5-flash'. Use /model to change." + ANSI_RESET);
                    currentModelName = "gemini-1.5-flash"; // Sane default
                } else {
                    System.out.println(ANSI_BLUE + "Invalid selection." + ANSI_RESET);
                }
                runner = runnerFactory.apply(currentModelName, currentProvider);
                System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                continue;
            }

            if ("/init".equalsIgnoreCase(line.trim())) {
                String currentPath = Paths.get("").toAbsolutePath().toString();
                String existing = centralMemory.getMemory(currentPath);
                if (existing != null && !existing.isBlank()) {
                    System.out.println(ANSI_BLUE + "Project already initialized in central memory." + ANSI_RESET);
                    System.out.println(ANSI_BLUE + "Use '/re-init' to force a fresh summary." + ANSI_RESET);
                    System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                    continue;
                }
                line = "Analyze the current project files (use `list_directory` and `read_file` as needed) to build a comprehensive summary of the project's purpose, architecture, and current state. Then, save this summary to the central database using the `save_central_memory` tool.";
                System.out.println(ANSI_BLUE + "System: Initializing project memory..." + ANSI_RESET);
            }

            if ("/re-init".equalsIgnoreCase(line.trim())) {
                 line = "Analyze the current project files (use `list_directory` and `read_file` as needed) to build a comprehensive summary of the project's purpose, architecture, and current state. Then, save this summary to the central database using the `save_central_memory` tool.";
                 System.out.println(ANSI_BLUE + "System: Re-initializing project memory..." + ANSI_RESET);
            }

            if ("/remember".equalsIgnoreCase(line.trim())) {
                 line = "Analyze the current project files (use `list_directory` and `read_file` as needed) to build a comprehensive summary of the project's purpose, architecture, and current state. Then, save this summary to the central database using the `save_central_memory` tool.";
                 System.out.println(ANSI_BLUE + "System: Initiating project analysis and memory storage..." + ANSI_RESET);
            }

            if ("/models".equalsIgnoreCase(line.trim())) {
                if (currentProvider == Provider.GEMINI) {
                    System.out.println(ANSI_BLUE + "Gemini Models:" + ANSI_RESET);
                    for (String m : GEMINI_MODELS) {
                        System.out.println(ANSI_BRIGHT_GREEN + "  - " + m + (m.equals(currentModelName) ? " (current)" : "") + ANSI_RESET);
                    }
                } else {
                    System.out.println(ANSI_BLUE + "Fetching available Ollama models..." + ANSI_RESET);
                    try {
                        HttpClient client = HttpClient.newHttpClient();
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:11434/api/tags"))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() == 200) {
                            System.out.println(ANSI_BLUE + "Ollama Models:" + ANSI_RESET);
                            String body = response.body();
                            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"name\":\"([^\"]+)\"");
                            java.util.regex.Matcher matcher = pattern.matcher(body);
                            boolean found = false;
                            while (matcher.find()) {
                                String m = matcher.group(1);
                                System.out.println(ANSI_BRIGHT_GREEN + "  - " + m + (m.equals(currentModelName) ? " (current)" : "") + ANSI_RESET);
                                found = true;
                            }
                            if (!found) {
                                System.out.println(ANSI_BLUE + "  No models found." + ANSI_RESET);
                            }
                        } else {
                            System.err.println(ANSI_BLUE + "Error: Ollama returned status " + response.statusCode() + ANSI_RESET);
                        }
                    } catch (Exception e) {
                        System.err.println(ANSI_BLUE + "Error fetching models: " + e.getMessage() + ANSI_RESET);
                    }
                }
                System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                continue;
            }
            
            if ("/model".equalsIgnoreCase(line.trim())) {
                List<String> availableModels = new ArrayList<>();
                if (currentProvider == Provider.GEMINI) {
                    availableModels.addAll(GEMINI_MODELS);
                } else {
                    System.out.println(ANSI_BLUE + "Fetching available Ollama models for selection..." + ANSI_RESET);
                    try {
                        HttpClient client = HttpClient.newHttpClient();
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:11434/api/tags"))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() == 200) {
                            String body = response.body();
                            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"name\":\"([^\"]+)\"");
                            java.util.regex.Matcher matcher = pattern.matcher(body);
                            while (matcher.find()) {
                                availableModels.add(matcher.group(1));
                            }
                        } else {
                            System.err.println(ANSI_BLUE + "Error: Ollama returned status " + response.statusCode() + ANSI_RESET);
                        }
                    } catch (Exception e) {
                        System.err.println(ANSI_BLUE + "Error fetching models: " + e.getMessage() + ANSI_RESET);
                    }
                }

                if (availableModels.isEmpty()) {
                    System.out.println(ANSI_BLUE + "No models available for selection." + ANSI_RESET);
                } else {
                    System.out.println(ANSI_BLUE + "Select a model (" + currentProvider + "):" + ANSI_RESET);
                    int defaultIndex = -1;
                    for (int i = 0; i < availableModels.size(); i++) {
                        String m = availableModels.get(i);
                        String marker = "";
                        if (m.equals(currentModelName)) {
                            marker = " (current)";
                            defaultIndex = i + 1;
                        }
                        System.out.println(ANSI_BRIGHT_GREEN + "[" + (i + 1) + "] " + m + marker + ANSI_RESET);
                    }
                    
                    System.out.print(ANSI_BLUE + "Enter selection (default " + (defaultIndex != -1 ? defaultIndex : "none") + "): " + ANSI_YELLOW);
                    String selection = scanner.nextLine().trim();
                    System.out.print(ANSI_RESET);
                    
                    if (selection.isEmpty()) {
                        if (defaultIndex != -1) {
                            System.out.println(ANSI_BLUE + "Keeping current model: " + currentModelName + ANSI_RESET);
                        } else {
                            System.out.println(ANSI_BLUE + "No selection made." + ANSI_RESET);
                        }
                    } else {
                        try {
                            int index = Integer.parseInt(selection);
                            if (index >= 1 && index <= availableModels.size()) {
                                String newModel = availableModels.get(index - 1);
                                if (!newModel.equals(currentModelName)) {
                                    System.out.println(ANSI_BLUE + "Switching model to: " + newModel + "..." + ANSI_RESET);
                                    currentModelName = newModel;
                                    runner = runnerFactory.apply(currentModelName, currentProvider);
                                    System.out.println(ANSI_BLUE + "Model switched successfully." + ANSI_RESET);
                                } else {
                                    System.out.println(ANSI_BLUE + "Model already selected." + ANSI_RESET);
                                }
                            } else {
                                System.out.println(ANSI_BLUE + "Invalid selection." + ANSI_RESET);
                            }
                        } catch (NumberFormatException e) {
                            System.out.println(ANSI_BLUE + "Invalid input." + ANSI_RESET);
                        }
                    }
                }
                System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                continue;
            }

            if ("/reset".equalsIgnoreCase(line.trim())) {
                currentSession = sessionService.createSession("mkpro-cli", "user").blockingGet();
                System.out.println(ANSI_BLUE + "System: Session reset. New session ID: " + currentSession.id() + ANSI_RESET);
                logger.log("SYSTEM", "Session reset by user.");
                System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                continue;
            }

            if ("/compact".equalsIgnoreCase(line.trim())) {
                System.out.println(ANSI_BLUE + "System: Compacting session..." + ANSI_RESET);
                
                // 1. Request Summary from Current Session
                StringBuilder summaryBuilder = new StringBuilder();
                Content summaryRequest = Content.builder()
                        .role("user")
                        .parts(Collections.singletonList(Part.fromText(
                            "Summarize our conversation so far, focusing on key technical decisions, " +
                            "user preferences, and current state. " +
                            "This summary will be used to initialize a new, compacted session."
                        )))
                        .build();

                try {
                    runner.runAsync("user", currentSession.id(), summaryRequest)
                        .filter(event -> event.content().isPresent())
                        .blockingForEach(event -> 
                            event.content().flatMap(Content::parts).orElse(Collections.emptyList())
                                .forEach(p -> p.text().ifPresent(summaryBuilder::append))
                        );
                } catch (Exception e) {
                     System.err.println(ANSI_BLUE + "Error generating summary: " + e.getMessage() + ANSI_RESET);
                     System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                     continue;
                }
                
                String summary = summaryBuilder.toString();
                if (summary.isBlank()) {
                     System.err.println(ANSI_BLUE + "Error: Agent returned empty summary." + ANSI_RESET);
                     System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                     continue;
                }

                // 2. Create New Session
                currentSession = sessionService.createSession("mkpro-cli", "user").blockingGet();
                String newSessionId = currentSession.id();
                
                System.out.println(ANSI_BLUE + "System: Session compacted. New Session ID: " + newSessionId + ANSI_RESET);
                logger.log("SYSTEM", "Session compacted. Summary: " + summary);
                
                // 3. Seed New Session by updating 'line' and falling through
                line = "Here is the summary of the previous session. Please use this as context for our continued conversation:\n\n" + summary;
            }

            if ("/summarize".equalsIgnoreCase(line.trim())) {
                 line = "Retrieve the action logs using the 'get_action_logs' tool. Then, summarize the key technical context, user preferences, and important decisions from these logs. Finally, write this summary to a file named 'session_summary.txt' using the 'write_file' tool. The summary should be concise and suitable for priming a future session.";
                 System.out.println(ANSI_BLUE + "System: Requesting session summary..." + ANSI_RESET);
            }

            logger.log("USER", line);

            java.util.List<Part> parts = new java.util.ArrayList<>();
            parts.add(Part.fromText(line));

            // Detect image paths in the input
            String[] tokens = line.split("\\s+");
            for (String token : tokens) {
                String lowerToken = token.toLowerCase();
                if (lowerToken.endsWith(".jpg") || lowerToken.endsWith(".jpeg") || 
                    lowerToken.endsWith(".png") || lowerToken.endsWith(".webp")) {
                    try {
                        Path imagePath = Paths.get(token);
                        if (Files.exists(imagePath)) {
                            if (verbose) System.out.println(ANSI_BLUE + "[DEBUG] Feeding image to agent: " + token + ANSI_RESET);
                            byte[] rawBytes = Files.readAllBytes(imagePath);
                            
                            // User previously suggested Base64, but "failed to process inputs: image: unknown format"
                            // strongly suggests the backend received double-encoded data or expects raw bytes.
                            // Let's try sending RAW BYTES. Part.fromBytes usually expects raw data.
                            
                            if (verbose) {
                                System.out.print(ANSI_BLUE + "[DEBUG] First 10 bytes: ");
                                for(int i=0; i<Math.min(10, rawBytes.length); i++) {
                                    System.out.printf("%02X ", rawBytes[i]);
                                }
                                System.out.println(ANSI_RESET);
                            }

                            String mimeType = "image/jpeg";
                            if (lowerToken.endsWith(".png")) mimeType = "image/png";
                            else if (lowerToken.endsWith(".webp")) mimeType = "image/webp";
                            
                            parts.add(Part.fromBytes(rawBytes, mimeType));
                        }
                    } catch (Exception e) {
                        if (verbose) System.err.println(ANSI_BLUE + "Warning: Could not read image file " + token + ": " + e.getMessage() + ANSI_RESET);
                    }
                }
            }

            Content content = Content.builder()
                    .role("user")
                    .parts(parts)
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
                
                runner.runAsync("user", currentSession.id(), content)
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
