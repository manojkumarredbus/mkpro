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

public class MkPro {

    public static void main(String[] args) {
        System.out.println("Initializing mkpro assistant...");
        
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: GOOGLE_API_KEY environment variable not set.");
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
                String filePath = (String) args.get("file_path");
                try {
                    Path path = Paths.get(filePath);
                    if (!Files.exists(path)) {
                         return Single.just(Collections.singletonMap("error", "File not found: " + filePath));
                    }
                    String content = Files.readString(path);
                    // Truncate if too long
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
                "Fetches the text content of a given URL for research purposes."
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
                                                .description("The URL to fetch content from.")
                                                .build()
                                ))
                                .required(ImmutableList.of("url"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String url = (String) args.get("url");
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
                        // Very basic HTML stripping
                        String text = html.replaceAll("<style.*?>.*?</style>", "")
                                          .replaceAll("<script.*?>.*?</script>", "")
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

        LlmAgent agent = LlmAgent.builder()
                .name("mkpro")
                .description("A helpful coding and research assistant.")
                .instruction("You are mkpro, a coding assistant running in the terminal. "
                        + "You can read files, list directories, and fetch URLs to help the user. "
                        + "Use Google Search for general questions and fetch_url to read specific documentation pages."
                        + "Always prefer concise answers.")
                //.model("gemini-2.0-flash-exp") // Updated to 2.0-flash-exp for search capability
                .model(new OllamaBaseLM("qwen3-vl:latest","http://localhost:11434"))
                .tools(readFileTool, listDirTool, urlFetchTool )//GoogleSearchTool.INSTANCE
                .build();

        Runner runner = Runner.builder()
                .agent(agent)
                .appName("mkpro-cli")
                .sessionService(sessionService)
                .artifactService(artifactService)
                .memoryService(memoryService)
                .build();

        Session session = sessionService.createSession("mkpro-cli", "user").blockingGet();

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
