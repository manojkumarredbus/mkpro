package com.mkpro;

import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import com.mkpro.models.AgentConfig;
import com.mkpro.models.Provider;
import com.mkpro.agents.AgentManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

public class MkPro {

    // ANSI Color Constants
    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BRIGHT_GREEN = "\u001b[92m";
    public static final String ANSI_YELLOW = "\u001b[33m"; // Closest to Orange
    public static final String ANSI_BLUE = "\u001b[34m";

    private static final List<String> GEMINI_MODELS = Arrays.asList(
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
        "gemini-1.5-flash",
        "gemini-1.5-flash-8b",
        "gemini-1.5-pro",
        "gemini-2.0-flash-exp"
    );

    private static final List<String> BEDROCK_MODELS = Arrays.asList(
        "anthropic.claude-3-sonnet-20240229-v1:0",
        "anthropic.claude-3-haiku-20240307-v1:0",
        "anthropic.claude-3-5-sonnet-20240620-v1:0",
        "meta.llama3-70b-instruct-v1:0",
        "meta.llama3-8b-instruct-v1:0",
        "amazon.titan-text-express-v1"
    );

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

        AgentManager agentManager = new AgentManager(sessionService, artifactService, memoryService, apiKey, logger, centralMemory);

        // Factory to create Runner with specific model
        Function<Map<String, AgentConfig>, Runner> runnerFactory = (agentConfigs) -> 
            agentManager.createRunner(agentConfigs, finalSummaryContext);

        if (useUI) {
            if (isVerbose) System.out.println(ANSI_BLUE + "Launching Swing Companion UI..." + ANSI_RESET);
            Map<String, AgentConfig> uiConfigs = new java.util.HashMap<>();
            uiConfigs.put("Coordinator", new AgentConfig(Provider.OLLAMA, modelName));
            uiConfigs.put("Coder", new AgentConfig(Provider.OLLAMA, modelName));
            uiConfigs.put("SysAdmin", new AgentConfig(Provider.OLLAMA, modelName));
            uiConfigs.put("Tester", new AgentConfig(Provider.OLLAMA, modelName));
            uiConfigs.put("DocWriter", new AgentConfig(Provider.OLLAMA, modelName));
            
            Runner runner = runnerFactory.apply(uiConfigs);
            SwingCompanion gui = new SwingCompanion(runner, mkSession, sessionService);
            gui.show();
        } else {
            // Default provider OLLAMA
            runConsoleLoop(runnerFactory, modelName, Provider.OLLAMA, mkSession, sessionService, centralMemory, logger, isVerbose);
        }
        
        logger.close();
    }

    private static void runConsoleLoop(Function<Map<String, AgentConfig>, Runner> runnerFactory, String initialModelName, Provider initialProvider, Session initialSession, InMemorySessionService sessionService, CentralMemory centralMemory, ActionLogger logger, boolean verbose) {
        // Initialize default configs for all agents
        Map<String, AgentConfig> agentConfigs = new java.util.HashMap<>();
        
        // Defaults
        agentConfigs.put("Coordinator", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("Coder", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("SysAdmin", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("Tester", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("DocWriter", new AgentConfig(initialProvider, initialModelName));

        // Load overrides from Central Memory
        try {
            Map<String, String> storedConfigs = centralMemory.getAgentConfigs();
            for (Map.Entry<String, String> entry : storedConfigs.entrySet()) {
                String agent = entry.getKey();
                String val = entry.getValue();
                if (val != null && val.contains("|")) {
                    String[] parts = val.split("\\|", 2);
                    try {
                        Provider p = Provider.valueOf(parts[0]);
                        String m = parts[1];
                        agentConfigs.put(agent, new AgentConfig(p, m));
                    } catch (IllegalArgumentException e) {
                        System.err.println(ANSI_BLUE + "Warning: Invalid provider in saved config for " + agent + ": " + parts[0] + ANSI_RESET);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println(ANSI_BLUE + "Warning: Failed to load agent configs from central memory: " + e.getMessage() + ANSI_RESET);
        }

        Runner runner = runnerFactory.apply(agentConfigs);
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
                System.out.println(ANSI_BLUE + "  /config     - Configure a specific agent (e.g., '/config Coder GEMINI gemini-1.5-pro')." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /provider   - Switch Coordinator provider (shortcut)." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /models     - List available models (for Coordinator's provider)." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /model      - Change Coordinator model (shortcut)." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /status     - Show current configuration for all agents." + ANSI_RESET);
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
            
            if ("/status".equalsIgnoreCase(line.trim())) {
                System.out.println(ANSI_BLUE + "+--------------+------------+------------------------------------------+" + ANSI_RESET);
                System.out.println(ANSI_BLUE + "| Agent        | Provider   | Model                                    |" + ANSI_RESET);
                System.out.println(ANSI_BLUE + "+--------------+------------+------------------------------------------+" + ANSI_RESET);
                
                List<String> sortedNames = new ArrayList<>(agentConfigs.keySet());
                Collections.sort(sortedNames);
                
                for (String name : sortedNames) {
                    AgentConfig ac = agentConfigs.get(name);
                    System.out.printf(ANSI_BLUE + "| " + ANSI_BRIGHT_GREEN + "%-12s " + ANSI_BLUE + "| " + ANSI_BRIGHT_GREEN + "%-10s " + ANSI_BLUE + "| " + ANSI_BRIGHT_GREEN + "%-40s " + ANSI_BLUE + "|%n" + ANSI_RESET, 
                        name, ac.getProvider(), ac.getModelName());
                }
                System.out.println(ANSI_BLUE + "+--------------+------------+------------------------------------------+" + ANSI_RESET);
                
                // Memory Status
                System.out.println("");
                System.out.println(ANSI_BLUE + "Memory Status:" + ANSI_RESET);
                System.out.println(ANSI_BRIGHT_GREEN + "  Local Session ID : " + currentSession.id() + ANSI_RESET);
                try {
                    String centralPath = Paths.get(System.getProperty("user.home"), ".mkpro", "central_memory.db").toString();
                    Map<String, String> memories = centralMemory.getAllMemories();
                    System.out.println(ANSI_BRIGHT_GREEN + "  Central Store    : " + centralPath + ANSI_RESET);
                    System.out.println(ANSI_BRIGHT_GREEN + "  Stored Projects  : " + memories.size() + ANSI_RESET);
                } catch (Exception e) {
                    System.out.println(ANSI_BRIGHT_GREEN + "  Central Store    : [Error accessing DB] " + e.getMessage() + ANSI_RESET);
                }
                
                System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                continue;
            }

            if (line.trim().toLowerCase().startsWith("/config")) {
                String[] parts = line.trim().split("\\s+");
                
                // Interactive Mode
                if (parts.length == 1) {
                    // 1. Select Agent
                    System.out.println(ANSI_BLUE + "Select Agent to configure:" + ANSI_RESET);
                    List<String> agentNames = new ArrayList<>(agentConfigs.keySet());
                    Collections.sort(agentNames); 
                    for (int i = 0; i < agentNames.size(); i++) {
                        AgentConfig ac = agentConfigs.get(agentNames.get(i));
                        System.out.printf(ANSI_BRIGHT_GREEN + "  [%d] %s (Current: %s - %s)%n" + ANSI_RESET, 
                            i + 1, agentNames.get(i), ac.getProvider(), ac.getModelName());
                    }
                    System.out.print(ANSI_BLUE + "Enter selection (number): " + ANSI_YELLOW);
                    String agentSelection = scanner.nextLine().trim();
                    System.out.print(ANSI_RESET);
                    
                    if (agentSelection.isEmpty()) continue;
                    
                    String selectedAgent = null;
                    try {
                        int idx = Integer.parseInt(agentSelection) - 1;
                        if (idx >= 0 && idx < agentNames.size()) {
                            selectedAgent = agentNames.get(idx);
                        }
                    } catch (NumberFormatException e) {}
                    
                    if (selectedAgent == null) {
                        System.out.println(ANSI_BLUE + "Invalid selection." + ANSI_RESET);
                        System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                        continue;
                    }

                    // 2. Select Provider
                    System.out.println(ANSI_BLUE + "Select Provider for " + selectedAgent + ":" + ANSI_RESET);
                    Provider[] providers = Provider.values();
                    for (int i = 0; i < providers.length; i++) {
                        System.out.printf(ANSI_BRIGHT_GREEN + "  [%d] %s%n" + ANSI_RESET, i + 1, providers[i]);
                    }
                    System.out.print(ANSI_BLUE + "Enter selection (number): " + ANSI_YELLOW);
                    String providerSelection = scanner.nextLine().trim();
                    System.out.print(ANSI_RESET);
                    
                    if (providerSelection.isEmpty()) continue;
                    
                    Provider selectedProvider = null;
                    try {
                        int idx = Integer.parseInt(providerSelection) - 1;
                        if (idx >= 0 && idx < providers.length) {
                            selectedProvider = providers[idx];
                        }
                    } catch (NumberFormatException e) {}
                    
                    if (selectedProvider == null) {
                        System.out.println(ANSI_BLUE + "Invalid selection." + ANSI_RESET);
                        System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                        continue;
                    }

                    // 3. Select Model
                    List<String> availableModels = new ArrayList<>();
                    if (selectedProvider == Provider.GEMINI) {
                        availableModels.addAll(GEMINI_MODELS);
                    } else if (selectedProvider == Provider.BEDROCK) {
                        availableModels.addAll(BEDROCK_MODELS);
                    } else if (selectedProvider == Provider.OLLAMA) {
                        System.out.println(ANSI_BLUE + "Fetching available Ollama models..." + ANSI_RESET);
                        try {
                            HttpClient client = HttpClient.newHttpClient();
                            HttpRequest request = HttpRequest.newBuilder()
                                    .uri(URI.create("http://localhost:11434/api/tags"))
                                    .timeout(Duration.ofSeconds(5))
                                    .GET()
                                    .build();
                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                            if (response.statusCode() == 200) {
                                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"name\":\"([^\"]+)\"").matcher(response.body());
                                while (matcher.find()) availableModels.add(matcher.group(1));
                            }
                        } catch (Exception e) {
                            System.out.println(ANSI_BLUE + "Could not fetch Ollama models. You can type the model name manually." + ANSI_RESET);
                        }
                    }

                    String selectedModel = null;
                    if (!availableModels.isEmpty()) {
                        System.out.println(ANSI_BLUE + "Select Model:" + ANSI_RESET);
                        for (int i = 0; i < availableModels.size(); i++) {
                            System.out.printf(ANSI_BRIGHT_GREEN + "  [%d] %s%n" + ANSI_RESET, i + 1, availableModels.get(i));
                        }
                        System.out.println(ANSI_BRIGHT_GREEN + "  [M] Manual Entry" + ANSI_RESET);
                        
                        System.out.print(ANSI_BLUE + "Enter selection: " + ANSI_YELLOW);
                        String modelSel = scanner.nextLine().trim();
                        System.out.print(ANSI_RESET);
                        
                        if (!"M".equalsIgnoreCase(modelSel)) {
                            try {
                                int idx = Integer.parseInt(modelSel) - 1;
                                if (idx >= 0 && idx < availableModels.size()) {
                                    selectedModel = availableModels.get(idx);
                                }
                            } catch (NumberFormatException e) {}
                        }
                    }

                    if (selectedModel == null) {
                        System.out.print(ANSI_BLUE + "Enter model name manually: " + ANSI_YELLOW);
                        selectedModel = scanner.nextLine().trim();
                        System.out.print(ANSI_RESET);
                    }

                    if (selectedModel.isEmpty()) {
                         System.out.println(ANSI_BLUE + "Model selection cancelled." + ANSI_RESET);
                         System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                         continue;
                    }

                    // Apply Configuration
                    agentConfigs.put(selectedAgent, new AgentConfig(selectedProvider, selectedModel));
                    centralMemory.saveAgentConfig(selectedAgent, selectedProvider.name(), selectedModel);
                    System.out.println(ANSI_BLUE + "Updated " + selectedAgent + " to [" + selectedProvider + "] " + selectedModel + ANSI_RESET);
                    
                    if ("Coordinator".equalsIgnoreCase(selectedAgent)) {
                        runner = runnerFactory.apply(agentConfigs);
                        System.out.println(ANSI_BLUE + "Coordinator runner rebuilt." + ANSI_RESET);
                    }

                } else if (parts.length >= 3) {
                    // Command Line Mode (legacy)
                    String agentName = parts[1];
                    String providerStr = parts[2].toUpperCase();
                    
                    if (!agentConfigs.containsKey(agentName)) {
                        System.out.println(ANSI_BLUE + "Unknown agent: " + agentName + ". Available: " + agentConfigs.keySet() + ANSI_RESET);
                    } else {
                        try {
                            Provider newProvider = Provider.valueOf(providerStr);
                            String newModel = (parts.length > 3) ? parts[3] : agentConfigs.get(agentName).getModelName(); 
                            
                            if (parts.length == 3 && newProvider != agentConfigs.get(agentName).getProvider()) {
                                if (newProvider == Provider.GEMINI) newModel = "gemini-1.5-flash";
                                else if (newProvider == Provider.BEDROCK) newModel = "anthropic.claude-3-sonnet-20240229-v1:0";
                                else if (newProvider == Provider.OLLAMA) newModel = "devstral-small-2";
                            }

                            agentConfigs.put(agentName, new AgentConfig(newProvider, newModel));
                            centralMemory.saveAgentConfig(agentName, newProvider.name(), newModel);
                            System.out.println(ANSI_BLUE + "Updated " + agentName + " to [" + newProvider + "] " + newModel + ANSI_RESET);
                            
                            if ("Coordinator".equalsIgnoreCase(agentName)) {
                                runner = runnerFactory.apply(agentConfigs);
                            }
                        } catch (IllegalArgumentException e) {
                            System.out.println(ANSI_BLUE + "Invalid provider: " + providerStr + ". Use OLLAMA, GEMINI, or BEDROCK." + ANSI_RESET);
                        }
                    }
                } else {
                     System.out.println(ANSI_BLUE + "Usage: /config (interactive) OR /config <Agent> <Provider> [Model]" + ANSI_RESET);
                }
                System.out.print(ANSI_BLUE + "> " + ANSI_YELLOW);
                continue;
            }

            if ("/provider".equalsIgnoreCase(line.trim())) {
                AgentConfig coordConfig = agentConfigs.get("Coordinator");
                System.out.println(ANSI_BLUE + "Current Coordinator Provider: " + coordConfig.getProvider() + ANSI_RESET);
                System.out.println(ANSI_BLUE + "Select new provider for Coordinator:" + ANSI_RESET);
                System.out.println(ANSI_BRIGHT_GREEN + "[1] OLLAMA" + ANSI_RESET);
                System.out.println(ANSI_BRIGHT_GREEN + "[2] GEMINI" + ANSI_RESET);
                System.out.println(ANSI_BRIGHT_GREEN + "[3] BEDROCK" + ANSI_RESET);
                System.out.print(ANSI_BLUE + "Enter selection: " + ANSI_YELLOW);
                String selection = scanner.nextLine().trim();
                System.out.print(ANSI_RESET);
                
                Provider newProvider = null;
                String newModel = coordConfig.getModelName();

                if ("1".equals(selection)) {
                    newProvider = Provider.OLLAMA;
                    System.out.println(ANSI_BLUE + "Switched to OLLAMA." + ANSI_RESET);
                } else if ("2".equals(selection)) {
                    newProvider = Provider.GEMINI;
                    newModel = "gemini-1.5-flash";
                    System.out.println(ANSI_BLUE + "Switched to GEMINI. Defaulting to 'gemini-1.5-flash'." + ANSI_RESET);
                } else if ("3".equals(selection)) {
                    newProvider = Provider.BEDROCK;
                    newModel = "anthropic.claude-3-sonnet-20240229-v1:0";
                    System.out.println(ANSI_BLUE + "Switched to BEDROCK. Defaulting to 'anthropic.claude-3-sonnet-20240229-v1:0'." + ANSI_RESET);
                } else {
                    System.out.println(ANSI_BLUE + "Invalid selection." + ANSI_RESET);
                }
                
                if (newProvider != null) {
                    agentConfigs.put("Coordinator", new AgentConfig(newProvider, newModel));
                    centralMemory.saveAgentConfig("Coordinator", newProvider.name(), newModel);
                    runner = runnerFactory.apply(agentConfigs);
                }
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
                AgentConfig coordConfig = agentConfigs.get("Coordinator");
                if (coordConfig.getProvider() == Provider.GEMINI) {
                    System.out.println(ANSI_BLUE + "Gemini Models:" + ANSI_RESET);
                    for (String m : GEMINI_MODELS) {
                        System.out.println(ANSI_BRIGHT_GREEN + "  - " + m + (m.equals(coordConfig.getModelName()) ? " (current)" : "") + ANSI_RESET);
                    }
                } else if (coordConfig.getProvider() == Provider.BEDROCK) {
                    System.out.println(ANSI_BLUE + "Bedrock Models:" + ANSI_RESET);
                    for (String m : BEDROCK_MODELS) {
                        System.out.println(ANSI_BRIGHT_GREEN + "  - " + m + (m.equals(coordConfig.getModelName()) ? " (current)" : "") + ANSI_RESET);
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
                                System.out.println(ANSI_BRIGHT_GREEN + "  - " + m + (m.equals(coordConfig.getModelName()) ? " (current)" : "") + ANSI_RESET);
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
                AgentConfig coordConfig = agentConfigs.get("Coordinator");
                List<String> availableModels = new ArrayList<>();
                if (coordConfig.getProvider() == Provider.GEMINI) {
                    availableModels.addAll(GEMINI_MODELS);
                } else if (coordConfig.getProvider() == Provider.BEDROCK) {
                    availableModels.addAll(BEDROCK_MODELS);
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
                    System.out.println(ANSI_BLUE + "Select a model (" + coordConfig.getProvider() + ")" + ANSI_RESET);
                    int defaultIndex = -1;
                    for (int i = 0; i < availableModels.size(); i++) {
                        String m = availableModels.get(i);
                        String marker = "";
                        if (m.equals(coordConfig.getModelName())) {
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
                            System.out.println(ANSI_BLUE + "Keeping current model: " + coordConfig.getModelName() + ANSI_RESET);
                        } else {
                            System.out.println(ANSI_BLUE + "No selection made." + ANSI_RESET);
                        }
                    } else {
                        try {
                            int index = Integer.parseInt(selection);
                            if (index >= 1 && index <= availableModels.size()) {
                                String newModel = availableModels.get(index - 1);
                                if (!newModel.equals(coordConfig.getModelName())) {
                                    System.out.println(ANSI_BLUE + "Switching Coordinator model to: " + newModel + "..." + ANSI_RESET);
                                    agentConfigs.put("Coordinator", new AgentConfig(coordConfig.getProvider(), newModel));
                                    centralMemory.saveAgentConfig("Coordinator", coordConfig.getProvider().name(), newModel);
                                    runner = runnerFactory.apply(agentConfigs);
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