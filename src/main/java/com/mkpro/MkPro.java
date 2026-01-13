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

public class MkPro {

    public static void main(String[] args) {
        System.out.println("Initializing mkpro assistant...");
        
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: GOOGLE_API_KEY environment variable not set.");
            System.exit(1);
        }

        // Check for UI flag
        boolean useUI = false;
        for (String arg : args) {
            if ("-ui".equalsIgnoreCase(arg) || "--companion".equalsIgnoreCase(arg)) {
                useUI = true;
                break;
            }
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
                System.out.println("[DEBUG] Tool invoked: read_file");
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
                System.out.println("[DEBUG] Tool invoked: list_directory");
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
                System.out.println("[DEBUG] Tool invoked: fetch_url");
                String url = (String) args.get("url");
                return Single.fromCallable(() -> {
                    try {
                        System.out.println("[DEBUG] Fetching URL: " + url);
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
                System.out.println("[DEBUG] Tool invoked: write_file");
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
                System.out.println("[DEBUG] Tool invoked: run_shell");
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

        LlmAgent agent = LlmAgent.builder()
                .name("mkpro")
                .description("A helpful coding and research assistant.")
                .instruction("You are mkpro, a powerful coding assistant. "
                        + "You have access to the local filesystem AND the internet. "
                        + "TOOLS AVAILABLE:\n"
                        + "- read_file: Read local files.\n"
                        + "- write_file: Create or overwrite files.\n"
                        + "- run_shell: Execute shell commands (git, etc).\n"
                        + "- list_directory: List files in folders.\n"
                        + "- fetch_url: Access external websites to read documentation. USE THIS when given a URL.\n"
                        + "- google_search: Search Google for general information.\n\n"
                        + "IMPORTANT: Before using write_file to modify code, you MUST use run_shell to 'git add .' and 'git commit' to save the current state.\n"
                        + "Always prefer concise answers.")
                //.model("gemini-2.0-flash-exp")
                .model(new OllamaBaseLM("devstral-small-2","http://localhost:11434"))
                .tools(readFileTool, writeFileTool, runShellTool, listDirTool, urlFetchTool)//, GoogleSearchTool.INSTANCE
                .build();

        Runner runner = Runner.builder()
                .agent(agent)
                .appName("mkpro-cli")
                .sessionService(sessionService)
                .artifactService(artifactService)
                .memoryService(memoryService)
                .build();

        Session session = sessionService.createSession("mkpro-cli", "user").blockingGet();

        if (useUI) {
            System.out.println("Launching Swing Companion UI...");
            SwingCompanion gui = new SwingCompanion(runner, session, sessionService);
            gui.show();
        } else {
            runConsoleLoop(runner, session);
        }
    }

    private static void runConsoleLoop(Runner runner, Session session) {
        System.out.println("mkpro ready! Type 'exit' to quit.");
        System.out.print("> ");

        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if ("exit".equalsIgnoreCase(line.trim())) {
                break;
            }

            Content content = Content.builder()
                    .role("user")
                    .parts(Collections.singletonList(Part.builder().text(line).build()))
                    .build();

            try {
                runner.runAsync("user", session.id(), content)
                        .filter(event -> event.content().isPresent())
                        .blockingForEach(event -> {
                            event.content()
                                .flatMap(Content::parts)
                                .orElse(Collections.emptyList())
                                .forEach(part -> 
                                    part.text().ifPresent(text -> System.out.print(text))
                                );
                        });
                System.out.println();
            } catch (Exception e) {
                System.err.println("Error processing request: " + e.getMessage());
                e.printStackTrace();
            }

            System.out.print("> ");
        }
        
        System.out.println("Goodbye!");
    }
}