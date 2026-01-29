package com.mkpro;

import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import com.mkpro.models.AgentConfig;
import com.mkpro.models.AgentStat;
import com.mkpro.models.Provider;
import com.mkpro.models.RunnerType;
import com.mkpro.models.ModelConfiguration;
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
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.google.genai.types.GenerateContentResponse;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import io.reactivex.rxjava3.disposables.Disposable;

import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

public class MkPro {

    // ANSI Color Constants
    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BRIGHT_GREEN = "\u001b[92m";
    public static final String ANSI_LIGHT_ORANGE = "\u001b[38;5;214m";
    public static final String ANSI_YELLOW = "\u001b[33m"; // Closest to Orange
    public static final String ANSI_BLUE = "\u001b[34m";


    public static void main(String[] args) {
        // Check for flags
        boolean useUI = false;
        boolean verbose = false;
        String initialModelName = ModelConfiguration.DEFAULT_OLLAMA_MODEL;
        Provider initialProvider = Provider.OLLAMA;
        RunnerType initialRunnerType = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-ui".equalsIgnoreCase(arg) || "--companion".equalsIgnoreCase(arg)) {
                useUI = true;
            } else if ("-v".equalsIgnoreCase(arg) || "--verbose".equalsIgnoreCase(arg)) {
                verbose = true;
            } else if ("-vb".equalsIgnoreCase(arg) || "--visible-browser".equalsIgnoreCase(arg)) {
                System.setProperty("mkpro.browser.visible", "true");
            } else if ("-m".equalsIgnoreCase(arg) || "--model".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    initialModelName = args[i + 1];
                    i++;
                }
            } else if ("-p".equalsIgnoreCase(arg) || "--provider".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    try {
                        initialProvider = Provider.valueOf(args[i + 1].toUpperCase());
                        // Update model to default for the selected provider if not explicitly set
                        if (initialModelName.equals(ModelConfiguration.DEFAULT_OLLAMA_MODEL)) {
                            initialModelName = ModelConfiguration.getDefaultModel(initialProvider);
                        }
                    } catch (IllegalArgumentException e) {
                        System.err.println("Invalid provider: " + args[i+1] + ". Valid options: OLLAMA, GEMINI, BEDROCK.");
                    }
                    i++;
                }
            } else if ("-r".equalsIgnoreCase(arg) || "--runner".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    try {
                        initialRunnerType = RunnerType.valueOf(args[i + 1].toUpperCase());
                    } catch (IllegalArgumentException e) {
                        System.err.println("Invalid runner type: " + args[i+1] + ". Valid options: IN_MEMORY, MAP_DB, POSTGRES.");
                    }
                    i++;
                }
            }
        }
        
        if (initialRunnerType == null && !useUI) {
            System.out.println(ANSI_BLUE + "Select Execution Runner:" + ANSI_RESET);
            System.out.println(ANSI_BRIGHT_GREEN + "[1] IN_MEMORY (Default, fast, ephemeral)" + ANSI_RESET);
            System.out.println(ANSI_BRIGHT_GREEN + "[2] MAP_DB (Persistent file-based)" + ANSI_RESET);
            System.out.println(ANSI_BRIGHT_GREEN + "[3] POSTGRES (Persistent relational DB)" + ANSI_RESET);
            System.out.print(ANSI_BLUE + "Enter selection [1]: " + ANSI_YELLOW);
            
            Scanner startupScanner = new Scanner(System.in);
            if (startupScanner.hasNextLine()) {
                String choice = startupScanner.nextLine().trim();
                if ("2".equals(choice)) {
                    initialRunnerType = RunnerType.MAP_DB;
                } else if ("3".equals(choice)) {
                    initialRunnerType = RunnerType.POSTGRES;
                } else {
                    initialRunnerType = RunnerType.IN_MEMORY;
                }
            } else {
                initialRunnerType = RunnerType.IN_MEMORY;
            }
            System.out.print(ANSI_RESET);
        } else if (initialRunnerType == null) {
            initialRunnerType = RunnerType.IN_MEMORY;
        }
        
        final String modelName = initialModelName;
        final Provider defaultProvider = initialProvider;
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
        Session mkSession = sessionService.createSession("mkpro", "Coordinator").blockingGet();
        mkSession.state().put("MKPRO", "REDBUS");
        ActionLogger logger = new ActionLogger("mkpro_logs.db");
        java.util.concurrent.atomic.AtomicReference<RunnerType> currentRunnerType = new java.util.concurrent.atomic.AtomicReference<>(initialRunnerType);

        // Factory to create Runner with specific model and runner type
        java.util.function.BiFunction<Map<String, AgentConfig>, RunnerType, Runner> runnerBuilder = (agentConfigs, rType) -> {
            AgentManager am = new AgentManager(sessionService, artifactService, memoryService, apiKey, logger, centralMemory, rType);
            return am.createRunner(agentConfigs, finalSummaryContext);
        };

        if (useUI) {
            if (isVerbose) System.out.println(ANSI_BLUE + "Launching Swing Companion UI..." + ANSI_RESET);
            Map<String, AgentConfig> uiConfigs = new java.util.HashMap<>();
            uiConfigs.put("Coordinator", new AgentConfig(defaultProvider, modelName));
            uiConfigs.put("Coder", new AgentConfig(defaultProvider, modelName));
            uiConfigs.put("SysAdmin", new AgentConfig(defaultProvider, modelName));
            uiConfigs.put("Tester", new AgentConfig(defaultProvider, modelName));
            uiConfigs.put("DocWriter", new AgentConfig(defaultProvider, modelName));
            uiConfigs.put("SecurityAuditor", new AgentConfig(defaultProvider, modelName));
            uiConfigs.put("Architect", new AgentConfig(defaultProvider, modelName));
            uiConfigs.put("DatabaseAdmin", new AgentConfig(defaultProvider, modelName));
            uiConfigs.put("DevOps", new AgentConfig(defaultProvider, modelName));
            uiConfigs.put("DataAnalyst", new AgentConfig(defaultProvider, modelName));
            uiConfigs.put("GoalTracker", new AgentConfig(defaultProvider, modelName));
            uiConfigs.put("CodeEditor", new AgentConfig(defaultProvider, modelName));
            
            Runner runner = runnerBuilder.apply(uiConfigs, currentRunnerType.get());
            SwingCompanion gui = new SwingCompanion(runner, mkSession);
            gui.show();
        } else {
            runConsoleLoop(runnerBuilder, currentRunnerType, modelName, defaultProvider, mkSession, sessionService, centralMemory, logger, isVerbose);
        }
        
        logger.close();
    }

    private static void saveSessionId(String sessionId) {
        try {
            Path mkproDir = Paths.get(System.getProperty("user.home"), ".mkpro");
            if (!Files.exists(mkproDir)) {
                Files.createDirectories(mkproDir);
            }
            Files.writeString(mkproDir.resolve("session_id"), sessionId);
        } catch (IOException e) {
            // Ignore errors
        }
    }

    private static String loadSessionId() {
        try {
            Path sessionFile = Paths.get(System.getProperty("user.home"), ".mkpro", "session_id");
            if (Files.exists(sessionFile)) {
                return Files.readString(sessionFile).trim();
            }
        } catch (IOException e) {
            // Ignore errors
        }
        return null;
    }

    private static void runConsoleLoop(java.util.function.BiFunction<Map<String, AgentConfig>, RunnerType, Runner> runnerBuilder, java.util.concurrent.atomic.AtomicReference<RunnerType> currentRunnerType, String initialModelName, Provider initialProvider, Session initialSession, InMemorySessionService sessionService, CentralMemory centralMemory, ActionLogger logger, boolean verbose) {
        // Initialize default configs for all agents
        Map<String, AgentConfig> agentConfigs = new java.util.HashMap<>();
        
        // Defaults
        agentConfigs.put("Coordinator", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("Coder", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("SysAdmin", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("Tester", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("DocWriter", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("SecurityAuditor", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("Architect", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("DatabaseAdmin", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("DevOps", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("DataAnalyst", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("GoalTracker", new AgentConfig(initialProvider, initialModelName));
        agentConfigs.put("CodeEditor", new AgentConfig(initialProvider, initialModelName));

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

        Runner runner = runnerBuilder.apply(agentConfigs, currentRunnerType.get());
        Session currentSession = runner.sessionService().createSession("mkpro", "Coordinator").blockingGet();
        
        Terminal terminal = null;
        LineReader lineReader = null;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            lineReader = LineReaderBuilder.builder().terminal(terminal).build();
        } catch (IOException e) {
            System.err.println("Error initializing JLine terminal: " + e.getMessage());
            System.exit(1);
        }
        final Terminal fTerminal = terminal;
        final LineReader fLineReader = lineReader;

        if (verbose) {
            System.out.println(ANSI_BLUE + "mkpro ready! Type 'exit' to quit." + ANSI_RESET);
        }
        System.out.println(ANSI_BLUE + "Type '/help' for a list of commands. Press [ESC] to interrupt agent." + ANSI_RESET);

        while (true) {
            String line = null;
            try {
                // ANSI colors in prompt: \u001b[34m> \u001b[33m
                line = fLineReader.readLine(ANSI_BLUE + "> " + ANSI_YELLOW); 
                System.out.print(ANSI_RESET); // Reset after input
            } catch (UserInterruptException e) {
                continue; 
            } catch (EndOfFileException e) {
                break;
            }

            if (line == null) break;
            
            line = line.trim();
            if ("exit".equalsIgnoreCase(line)) {
                break;
            }

            if ("/h".equalsIgnoreCase(line) || "/help".equalsIgnoreCase(line)) {
                System.out.println(ANSI_BLUE + "Available Commands:" + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /config     - Configure a specific agent." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /runner     - Change the execution runner." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /provider   - Switch Coordinator provider." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /models     - List available models." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /model      - Change Coordinator model." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /status     - Show current configuration." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /stats      - Show agent usage statistics." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /init       - Initialize project memory." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /re-init    - Re-initialize project memory." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /remember   - Analyze and save summary." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /reset      - Reset the session." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /compact    - Compact the session." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  /summarize  - Generate session summary." + ANSI_RESET);
                System.out.println(ANSI_BLUE + "  exit        - Quit." + ANSI_RESET);
                continue;
            }
            
            if ("/runner".equalsIgnoreCase(line)) {
                System.out.println(ANSI_BLUE + "Current Runner: " + currentRunnerType.get() + ANSI_RESET);
                System.out.println(ANSI_BLUE + "Select new Runner Type:" + ANSI_RESET);
                RunnerType[] types = RunnerType.values();
                for (int i = 0; i < types.length; i++) {
                    System.out.println(ANSI_BRIGHT_GREEN + "[" + (i + 1) + "] " + types[i] + ANSI_RESET);
                }
                
                String selection = lineReader.readLine(ANSI_BLUE + "Enter selection: " + ANSI_YELLOW).trim();
                System.out.print(ANSI_RESET);

                try {
                    int idx = Integer.parseInt(selection) - 1;
                    if (idx >= 0 && idx < types.length) {
                        RunnerType newType = types[idx];
                        if (newType == currentRunnerType.get()) {
                            System.out.println(ANSI_BLUE + "Already using " + newType + "." + ANSI_RESET);
                        } else {
                            System.out.println(ANSI_BLUE + "WARNING: Switching to " + newType + " will start a NEW session." + ANSI_RESET);
                            String confirm = lineReader.readLine(ANSI_BLUE + "Do you want to continue? (y/N): " + ANSI_YELLOW).trim();
                            System.out.print(ANSI_RESET);
                            
                            if ("y".equalsIgnoreCase(confirm) || "yes".equalsIgnoreCase(confirm)) {
                                currentRunnerType.set(newType);
                                System.out.println(ANSI_BLUE + "Switched to " + currentRunnerType.get() + ". Rebuilding runner..." + ANSI_RESET);
                                runner = runnerBuilder.apply(agentConfigs, currentRunnerType.get());
                                currentSession = runner.sessionService().createSession("mkpro", "Coordinator").blockingGet();
                                System.out.println(ANSI_BLUE + "Runner rebuilt. New Session ID: " + currentSession.id() + ANSI_RESET);
                            } else {
                                System.out.println(ANSI_BLUE + "Switch cancelled." + ANSI_RESET);
                            }
                        }
                    } else {
                        System.out.println(ANSI_BLUE + "Invalid selection." + ANSI_RESET);
                    }
                } catch (Exception e) {
                    System.out.println(ANSI_BLUE + "Invalid input or error rebuilding runner: " + e.getMessage() + ANSI_RESET);
                }
                continue;
            }

            if ("/stats".equalsIgnoreCase(line)) {
                try {
                    List<AgentStat> stats = centralMemory.getAgentStats();
                    if (stats.isEmpty()) {
                        System.out.println(ANSI_BLUE + "No statistics available yet." + ANSI_RESET);
                    } else {
                        System.out.println(ANSI_BLUE + "Agent Statistics:" + ANSI_RESET);
                        System.out.println(ANSI_BLUE + String.format("%-15s | %-10s | %-25s | %-8s | %-8s | %-8s", "Agent", "Provider", "Model", "Duration", "Success", "In/Out") + ANSI_RESET);
                        System.out.println(ANSI_BLUE + "-".repeat(95) + ANSI_RESET);
                        int start = Math.max(0, stats.size() - 20);
                        for (int i = start; i < stats.size(); i++) {
                            AgentStat s = stats.get(i);
                            String modelShort = s.getModel();
                            if (modelShort.length() > 25) modelShort = modelShort.substring(0, 22) + "...";
                            System.out.println(ANSI_BRIGHT_GREEN + String.format("%-15s | %-10s | %-25s | %-8dms | %-8s | %d/%d", s.getAgentName(), s.getProvider(), modelShort, s.getDurationMs(), s.isSuccess(), s.getInputLength(), s.getOutputLength()) + ANSI_RESET);
                        }
                        System.out.println(ANSI_BLUE + "-".repeat(95) + ANSI_RESET);
                        System.out.println(ANSI_BLUE + "Total Invocations: " + stats.size() + ANSI_RESET);
                    }
                } catch (Exception e) {
                    System.err.println(ANSI_BLUE + "Error retrieving stats: " + e.getMessage() + ANSI_RESET);
                }
                continue;
            }

            if ("/status".equalsIgnoreCase(line)) {
                System.out.println(ANSI_BLUE + "Runner Type : " + ANSI_BRIGHT_GREEN + currentRunnerType.get() + ANSI_RESET);
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
                continue;
            }

            if (line.toLowerCase().startsWith("/config")) {
                String[] parts = line.trim().split("\\s+");
                
                // Interactive Mode
                if (parts.length == 1) {
                    fTerminal.writer().println(ANSI_BLUE + "Select Agent to configure:" + ANSI_RESET);
                    List<String> agentNames = new ArrayList<>(agentConfigs.keySet());
                    Collections.sort(agentNames); 
                    for (int i = 0; i < agentNames.size(); i++) {
                        AgentConfig ac = agentConfigs.get(agentNames.get(i));
                        fTerminal.writer().printf(ANSI_BRIGHT_GREEN + "  [%d] %s (Current: %s - %s)%n" + ANSI_RESET, 
                            i + 1, agentNames.get(i), ac.getProvider(), ac.getModelName());
                    }
                    
                    String agentSelection = fLineReader.readLine(ANSI_BLUE + "Enter selection (number): " + ANSI_YELLOW).trim();
                    fTerminal.writer().print(ANSI_RESET);
                    
                    if (agentSelection.isEmpty()) continue;
                    
                    String selectedAgent = null;
                    try {
                        int idx = Integer.parseInt(agentSelection) - 1;
                        if (idx >= 0 && idx < agentNames.size()) {
                            selectedAgent = agentNames.get(idx);
                        }
                    } catch (NumberFormatException e) {}
                    
                    if (selectedAgent == null) {
                        fTerminal.writer().println(ANSI_BLUE + "Invalid selection." + ANSI_RESET);
                        continue;
                    }

                    // 2. Select Provider
                    fTerminal.writer().println(ANSI_BLUE + "Select Provider for " + selectedAgent + ":" + ANSI_RESET);
                    Provider[] providers = Provider.values();
                    for (int i = 0; i < providers.length; i++) {
                        fTerminal.writer().printf(ANSI_BRIGHT_GREEN + "  [%d] %s%n" + ANSI_RESET, i + 1, providers[i]);
                    }
                    
                    String providerSelection = fLineReader.readLine(ANSI_BLUE + "Enter selection (number): " + ANSI_YELLOW).trim();
                    fTerminal.writer().print(ANSI_RESET);
                    
                    if (providerSelection.isEmpty()) continue;
                    
                    Provider selectedProvider = null;
                    try {
                        int idx = Integer.parseInt(providerSelection) - 1;
                        if (idx >= 0 && idx < providers.length) {
                            selectedProvider = providers[idx];
                        }
                    } catch (NumberFormatException e) {}
                    
                    if (selectedProvider == null) {
                        fTerminal.writer().println(ANSI_BLUE + "Invalid selection." + ANSI_RESET);
                        continue;
                    }

                    // 3. Select Model
                    List<String> availableModels = new ArrayList<>();
                    List<String> providerModels = ModelConfiguration.getAvailableModels(selectedProvider);
                    if (!providerModels.isEmpty()) {
                        availableModels.addAll(providerModels);
                    } else if (selectedProvider == Provider.OLLAMA) {
                        fTerminal.writer().println(ANSI_BLUE + "Fetching available Ollama models..." + ANSI_RESET);
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
                            fTerminal.writer().println(ANSI_BLUE + "Could not fetch Ollama models. You can type the model name manually." + ANSI_RESET);
                        }
                    }

                    String selectedModel = null;
                    if (!availableModels.isEmpty()) {
                        fTerminal.writer().println(ANSI_BLUE + "Select Model:" + ANSI_RESET);
                        for (int i = 0; i < availableModels.size(); i++) {
                            fTerminal.writer().printf(ANSI_BRIGHT_GREEN + "  [%d] %s%n" + ANSI_RESET, i + 1, availableModels.get(i));
                        }
                        fTerminal.writer().println(ANSI_BRIGHT_GREEN + "  [M] Manual Entry" + ANSI_RESET);
                        
                        String modelSel = fLineReader.readLine(ANSI_BLUE + "Enter selection: " + ANSI_YELLOW).trim();
                        fTerminal.writer().print(ANSI_RESET);
                        
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
                        selectedModel = fLineReader.readLine(ANSI_BLUE + "Enter model name manually: " + ANSI_YELLOW).trim();
                        fTerminal.writer().print(ANSI_RESET);
                    }

                    if (selectedModel.isEmpty()) {
                         fTerminal.writer().println(ANSI_BLUE + "Model selection cancelled." + ANSI_RESET);
                         continue;
                    }

                    // Apply Configuration
                    agentConfigs.put(selectedAgent, new AgentConfig(selectedProvider, selectedModel));
                    centralMemory.saveAgentConfig(selectedAgent, selectedProvider.name(), selectedModel);
                    fTerminal.writer().println(ANSI_BLUE + "Updated " + selectedAgent + " to [" + selectedProvider + "] " + selectedModel + ANSI_RESET);
                    
                    if ("Coordinator".equalsIgnoreCase(selectedAgent)) {
                        runner = runnerBuilder.apply(agentConfigs, currentRunnerType.get());
                        fTerminal.writer().println(ANSI_BLUE + "Coordinator runner rebuilt." + ANSI_RESET);
                    }

                } else if (parts.length >= 3) {
                    // Command Line Mode (legacy)
                    String agentName = parts[1];
                    String providerStr = parts[2].toUpperCase();
                    
                    if (!agentConfigs.containsKey(agentName)) {
                        fTerminal.writer().println(ANSI_BLUE + "Unknown agent: " + agentName + ". Available: " + agentConfigs.keySet() + ANSI_RESET);
                    } else {
                        try {
                            Provider newProvider = Provider.valueOf(providerStr);
                            String newModel = (parts.length > 3) ? parts[3] : agentConfigs.get(agentName).getModelName(); 
                            
                            // If provider changed and no model specified, use default for new provider
                            if (parts.length == 3 && newProvider != agentConfigs.get(agentName).getProvider()) {
                                newModel = ModelConfiguration.getDefaultModel(newProvider);
                            }

                            agentConfigs.put(agentName, new AgentConfig(newProvider, newModel));
                            centralMemory.saveAgentConfig(agentName, newProvider.name(), newModel);
                            fTerminal.writer().println(ANSI_BLUE + "Updated " + agentName + " to [" + newProvider + "] " + newModel + ANSI_RESET);
                            
                            if ("Coordinator".equalsIgnoreCase(agentName)) {
                                runner = runnerBuilder.apply(agentConfigs, currentRunnerType.get());
                            }
                        } catch (IllegalArgumentException e) {
                            fTerminal.writer().println(ANSI_BLUE + "Invalid provider: " + providerStr + ". Use OLLAMA, GEMINI, or BEDROCK." + ANSI_RESET);
                        }
                    }
                } else {
                     fTerminal.writer().println(ANSI_BLUE + "Usage: /config (interactive) OR /config <Agent> <Provider> [Model]" + ANSI_RESET);
                }
                continue;
            }

            if ("/reset".equalsIgnoreCase(line)) {
                currentSession = runner.sessionService().createSession("mkpro", "Coordinator").blockingGet();
                System.out.println(ANSI_BLUE + "System: Session reset. New session ID: " + currentSession.id() + ANSI_RESET);
                logger.log("SYSTEM", "Session reset by user.");
                continue;
            }

            if ("/compact".equalsIgnoreCase(line)) {
                System.out.println(ANSI_BLUE + "System: Compacting session..." + ANSI_RESET);
                StringBuilder summaryBuilder = new StringBuilder();
                Content summaryRequest = Content.builder().role("user").parts(Collections.singletonList(Part.fromText("Summarize our conversation so far."))).build();
                try {
                    runner.runAsync("Coordinator", currentSession.id(), summaryRequest)
                        .filter(event -> event.content().isPresent())
                        .blockingForEach(event -> event.content().flatMap(Content::parts).orElse(Collections.emptyList()).forEach(p -> p.text().ifPresent(summaryBuilder::append)));
                } catch (Exception e) {
                     System.err.println(ANSI_BLUE + "Error generating summary: " + e.getMessage() + ANSI_RESET);
                     continue;
                }
                String summary = summaryBuilder.toString();
                if (summary.isBlank()) {
                     System.err.println(ANSI_BLUE + "Error: Agent returned empty summary." + ANSI_RESET);
                     continue;
                }
                currentSession = runner.sessionService().createSession("mkpro", "Coordinator").blockingGet();
                System.out.println(ANSI_BLUE + "System: Session compacted. New Session ID: " + currentSession.id() + ANSI_RESET);
                logger.log("SYSTEM", "Session compacted.");
                line = "Here is the summary of the previous session:\n\n" + summary;
            }

            if ("/summarize".equalsIgnoreCase(line)) {
                 line = "Retrieve the action logs using the 'get_action_logs' tool. Then, summarize the key technical context, user preferences, and important decisions. Write this summary to 'session_summary.txt'.";
                 System.out.println(ANSI_BLUE + "System: Requesting session summary..." + ANSI_RESET);
            }

            logger.log("USER", line);

            java.util.List<Part> parts = new java.util.ArrayList<>();
            parts.add(Part.fromText(line));

            // Image detection logic
            String[] tokens = line.split("\\s+");
            for (String token : tokens) {
                String lowerToken = token.toLowerCase();
                if (lowerToken.endsWith(".jpg") || lowerToken.endsWith(".jpeg") || lowerToken.endsWith(".png") || lowerToken.endsWith(".webp")) {
                    try {
                        Path imagePath = Paths.get(token);
                        if (Files.exists(imagePath)) {
                            if (verbose) System.out.println(ANSI_BLUE + "[DEBUG] Feeding image: " + token + ANSI_RESET);
                            byte[] rawBytes = Files.readAllBytes(imagePath);
                            String mimeType = lowerToken.endsWith(".png") ? "image/png" : (lowerToken.endsWith(".webp") ? "image/webp" : "image/jpeg");
                            parts.add(Part.fromBytes(rawBytes, mimeType));
                        }
                    } catch (Exception e) {
                        if (verbose) System.err.println(ANSI_BLUE + "Warning: Could not read image " + token + ANSI_RESET);
                    }
                }
            }

            Content content = Content.builder().role("user").parts(parts).build();

            // Interruption & Execution Logic
            AtomicBoolean isThinking = new AtomicBoolean(true);
            AtomicBoolean isCancelled = new AtomicBoolean(false);
            
            StringBuilder responseBuilder = new StringBuilder();
            Disposable agentSubscription = null;
            
                                    try {
                                        // Using var for type inference to match ADK return type exactly
                                        var flowable = runner.runAsync("Coordinator", currentSession.id(), content);
                                        
                                        agentSubscription = flowable
                                            .filter(event -> event.content().isPresent())
                                            .subscribe(
                                                event -> {
                                                                                event.content().flatMap(Content::parts).orElse(Collections.emptyList()).forEach(part -> 
                                                                                    part.text().ifPresent(text -> {
                                                                                        fTerminal.writer().print(ANSI_LIGHT_ORANGE + text);
                                                                                        fTerminal.writer().flush();
                                                                                        responseBuilder.append(text);
                                                                                    })
                                                                                );
                                                                            },
                                                                            error -> {
                                                                                isThinking.set(false);
                                                                                fTerminal.writer().println(ANSI_BLUE + "\nError processing request: " + error.getMessage() + ANSI_RESET);
                                                                                fTerminal.writer().flush();
                                                                                logger.log("ERROR", error.getMessage());
                                                                            },
                                                                            () -> {
                                                                                isThinking.set(false);
                                                                                fTerminal.writer().println(ANSI_RESET);
                                                                                fTerminal.writer().flush();
                                                                                logger.log("AGENT", responseBuilder.toString());
                                                                            }
                                                                        );
                                                    
                                                                    // Initial color set
                                                                    fTerminal.writer().print(ANSI_LIGHT_ORANGE);
                                                                    fTerminal.writer().flush();
                                                                    
                                                                    // Spinner chars
                                                                    String[] syms = {"|", "/", "-", "\\"};
                                                                    int spinnerIdx = 0;
                                                                    long lastSpinnerUpdate = 0;
                                                    
                                                                    while (isThinking.get()) {
                                                                        // Non-blocking read with timeout
                                                                        int c = fTerminal.reader().read(10); 
                                                                        if (c == 27) { // ESC
                                                                            isCancelled.set(true);
                                                                            agentSubscription.dispose();
                                                                            isThinking.set(false);
                                                                            fTerminal.writer().print(ANSI_RESET);
                                                                            fTerminal.writer().println(ANSI_BLUE + "\n[!] Interrupted by user." + ANSI_RESET);
                                                                            fTerminal.writer().flush();
                                                                            logger.log("SYSTEM", "User interrupted the agent.");
                                                                            break;
                                                                        }
                                                                        
                                                                        // Update spinner only if no response has started streaming yet
                                                                        if (responseBuilder.length() == 0) {
                                                                            long now = System.currentTimeMillis();
                                                                            if (now - lastSpinnerUpdate > 100) {
                                                                                fTerminal.writer().print("\r" + ANSI_BLUE + "Thinking " + syms[spinnerIdx++ % syms.length] + ANSI_RESET);
                                                                                fTerminal.writer().flush();
                                                                                lastSpinnerUpdate = now;
                                                                            }
                                                                        } else {
                                                                            // Once response starts, ensure we cleared the spinner line once
                                                                            if (spinnerIdx != -1) {
                                                                                 fTerminal.writer().print("\r" + " ".repeat(20) + "\r"); // Clear spinner
                                                                                 fTerminal.writer().print(ANSI_LIGHT_ORANGE + responseBuilder.toString()); // Reprint buffer to be safe
                                                                                 fTerminal.writer().flush();
                                                                                 spinnerIdx = -1; // Flag that we are done spinning
                                                                            }
                                                                        }
                                                                    }
                        
                                    } catch (Exception e) {
                                        System.err.println(ANSI_BLUE + "Error starting request: " + e.getMessage() + ANSI_RESET);
                                    }        }
        
        if (verbose) System.out.println(ANSI_BLUE + "Goodbye!" + ANSI_RESET);
    }
}
