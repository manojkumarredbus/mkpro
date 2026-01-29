package com.mkpro.agents;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.OllamaBaseLM;
import com.google.adk.models.Gemini;
import com.google.adk.models.BedrockBaseLM;
import com.google.adk.models.BaseLlm;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.MapDbRunner;
import com.google.adk.runner.PostgresRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;

import com.mkpro.models.AgentConfig;
import com.mkpro.models.AgentRequest;
import com.mkpro.models.AgentStat;
import com.mkpro.models.Provider;
import com.mkpro.models.RunnerType;
import com.mkpro.tools.MkProTools;
import com.mkpro.ActionLogger;
import com.mkpro.CentralMemory;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mkpro.models.AgentDefinition;
import com.mkpro.models.AgentsConfig;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.InputStream;
import java.util.HashMap;

public class AgentManager {

    private final InMemorySessionService sessionService;
    private final InMemoryArtifactService artifactService;
    private final InMemoryMemoryService memoryService;
    private final String apiKey;
    private final ActionLogger logger;
    private final CentralMemory centralMemory;
    private final RunnerType runnerType;
    private final Map<String, AgentDefinition> agentDefinitions;

    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BLUE = "\u001b[34m";

    private static final String BASE_AGENT_POLICY =
    "Authority:\n" +
    "- You are an autonomous specialist operating under the Coordinator agent.\n" +
    "- You MUST act only within the scope of your assigned responsibilities.\n" +
    "\n" +
    "General Rules:\n" +
    "- You MUST follow all explicit instructions provided by the Coordinator.\n" +
    "- You MUST analyze the task and relevant context before taking any action.\n" +
    "- You MUST produce deterministic, reproducible outputs.\n" +
    "- You SHOULD minimize unnecessary actions and side effects.\n" +
    "- You MUST clearly report what actions were taken and why.\n" +
    "- You MUST NOT assume missing information; request clarification when required.\n" +
    "\n" +
    "Tool Usage Policy:\n" +
    "- You MUST use only the tools explicitly available to you.\n" +
    "- You MUST NOT simulate or claim tool execution that did not occur.\n" +
    "- You SHOULD prefer read-only operations unless modification is explicitly required.\n" +
    "\n" +
    "Safety & Quality:\n" +
    "- You MUST preserve data integrity and avoid destructive actions.\n" +
    "- You SHOULD favor minimal, reversible changes.\n" +
    "- You MUST report errors, risks, or inconsistencies immediately.\n";

    public AgentManager(InMemorySessionService sessionService, 
                        InMemoryArtifactService artifactService, 
                        InMemoryMemoryService memoryService, 
                        String apiKey,
                        ActionLogger logger,
                        CentralMemory centralMemory,
                        RunnerType runnerType,
                        Path teamFilePath) {
        this.sessionService = sessionService;
        this.artifactService = artifactService;
        this.memoryService = memoryService;
        this.apiKey = apiKey;
        this.logger = logger;
        this.centralMemory = centralMemory;
        this.runnerType = runnerType;
        this.agentDefinitions = loadAgentDefinitions(teamFilePath);
    }

    private Map<String, AgentDefinition> loadAgentDefinitions(Path teamFilePath) {
        try (InputStream is = Files.newInputStream(teamFilePath)) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            AgentsConfig config = mapper.readValue(is, AgentsConfig.class);
            Map<String, AgentDefinition> map = new HashMap<>();
            for (AgentDefinition def : config.getAgents()) {
                map.put(def.getName(), def);
            }
            return map;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load agents from " + teamFilePath, e);
        }
    }

    public Runner createRunner(Map<String, AgentConfig> agentConfigs, String summaryContext) {
        String contextInfo = "\nCurrent Date: " + LocalDate.now() + "\nCurrent Working Directory: " + Paths.get("").toAbsolutePath().toString();

        // Coordinator Model
        AgentConfig coordConfig = agentConfigs.get("Coordinator");
        BaseLlm model = createModel(coordConfig);
        AgentDefinition coordDef = agentDefinitions.get("Coordinator");

        // Core Tools
        // ... (tools logic stays same)
        List<BaseTool> codeEditorTools = new ArrayList<>();
        codeEditorTools.add(MkProTools.createSafeWriteFileTool());
        codeEditorTools.add(MkProTools.createReadFileTool());

        List<BaseTool> coderTools = new ArrayList<>();
        coderTools.add(MkProTools.createReadFileTool());
        // coderTools.add(MkProTools.createWriteFileTool()); // Removed direct write access
        coderTools.add(MkProTools.createListDirTool());
        coderTools.add(MkProTools.createReadImageTool());

        List<BaseTool> sysAdminTools = new ArrayList<>();
        sysAdminTools.add(MkProTools.createRunShellTool());

        List<BaseTool> testerTools = new ArrayList<>();
        testerTools.addAll(coderTools); // Read/Write/List/Image
        testerTools.add(MkProTools.createRunShellTool());

        List<BaseTool> docWriterTools = new ArrayList<>();
        docWriterTools.add(MkProTools.createReadFileTool());
        docWriterTools.add(MkProTools.createWriteFileTool());
        docWriterTools.add(MkProTools.createListDirTool());

        List<BaseTool> securityAuditorTools = new ArrayList<>();
        securityAuditorTools.addAll(coderTools); // Read/Analyze code
        securityAuditorTools.add(MkProTools.createRunShellTool()); // Run audit tools

        List<BaseTool> architectTools = new ArrayList<>();
        architectTools.add(MkProTools.createReadFileTool());
        architectTools.add(MkProTools.createListDirTool());
        architectTools.add(MkProTools.createReadImageTool());

        List<BaseTool> databaseTools = new ArrayList<>();
        databaseTools.addAll(coderTools); // Read/Write SQL files, schemas

        List<BaseTool> devOpsTools = new ArrayList<>();
        devOpsTools.addAll(coderTools); // Read/Write configs (Dockerfiles, k8s, etc.)
        devOpsTools.add(MkProTools.createRunShellTool()); // Execute cloud CLIs, docker commands

        List<BaseTool> dataAnalystTools = new ArrayList<>();
        dataAnalystTools.addAll(coderTools); // Read/Write Python scripts and data files
        dataAnalystTools.add(MkProTools.createRunShellTool()); // Execute python scripts

        List<BaseTool> goalTrackerTools = new ArrayList<>();
        goalTrackerTools.add(MkProTools.createAddGoalTool(centralMemory));
        goalTrackerTools.add(MkProTools.createListGoalsTool(centralMemory));
        goalTrackerTools.add(MkProTools.createUpdateGoalTool(centralMemory));

        List<BaseTool> webTools = new ArrayList<>();
        webTools.add(com.mkpro.tools.SeleniumTools.createNavigateTool());
        webTools.add(com.mkpro.tools.SeleniumTools.createClickTool());
        webTools.add(com.mkpro.tools.SeleniumTools.createTypeTool());
        webTools.add(com.mkpro.tools.SeleniumTools.createScreenshotTool());
        webTools.add(com.mkpro.tools.SeleniumTools.createGetHtmlTool());
        webTools.add(com.mkpro.tools.SeleniumTools.createCloseTool());

        // Add web capabilities to specific agents
        testerTools.addAll(webTools);
        docWriterTools.addAll(webTools);

        // Delegation Tools
        List<BaseTool> coordinatorTools = new ArrayList<>();
        coordinatorTools.addAll(webTools); // Give Coordinator direct web access for research
        
        // Coder Sub-Agents
        coordinatorTools.add(createDelegationToolFromDef("CodeEditor", "ask_code_editor", agentConfigs, codeEditorTools, contextInfo));
        // Note: CodeEditor is a sub-agent of Coder in strict hierarchy, but here added to Coordinator for direct debugging if needed? 
        // Wait, previous code added it to `coderTools`. Let's stick to that.
        
        // Re-adding `ask_code_editor` to `coderTools` NOT coordinatorTools directly, unless specified.
        // The previous code had: `coderTools.add(createDelegationTool(..., "CodeEditor", ...))`
        coderTools.add(createDelegationToolFromDef("CodeEditor", "ask_code_editor", agentConfigs, codeEditorTools, contextInfo));


        coordinatorTools.add(createDelegationToolFromDef("GoalTracker", "ask_goal_tracker", agentConfigs, goalTrackerTools, contextInfo));
        coordinatorTools.add(createDelegationToolFromDef("Coder", "ask_coder", agentConfigs, coderTools, contextInfo));
        coordinatorTools.add(createDelegationToolFromDef("SysAdmin", "ask_sysadmin", agentConfigs, sysAdminTools, contextInfo));
        coordinatorTools.add(createDelegationToolFromDef("Tester", "ask_tester", agentConfigs, testerTools, contextInfo));
        coordinatorTools.add(createDelegationToolFromDef("DocWriter", "ask_doc_writer", agentConfigs, docWriterTools, contextInfo));
        coordinatorTools.add(createDelegationToolFromDef("SecurityAuditor", "ask_security_auditor", agentConfigs, securityAuditorTools, contextInfo));
        coordinatorTools.add(createDelegationToolFromDef("Architect", "ask_architect", agentConfigs, architectTools, contextInfo));
        coordinatorTools.add(createDelegationToolFromDef("DatabaseAdmin", "ask_database_admin", agentConfigs, databaseTools, contextInfo));
        coordinatorTools.add(createDelegationToolFromDef("DevOps", "ask_devops", agentConfigs, devOpsTools, contextInfo));
        coordinatorTools.add(createDelegationToolFromDef("DataAnalyst", "ask_data_analyst", agentConfigs, dataAnalystTools, contextInfo));

        // Add Coordinator-specific tools
        coordinatorTools.add(MkProTools.createUrlFetchTool());
        coordinatorTools.add(MkProTools.createGetActionLogsTool(logger));
        coordinatorTools.add(MkProTools.createSaveMemoryTool(centralMemory));
        coordinatorTools.add(MkProTools.createReadMemoryTool(centralMemory));
        coordinatorTools.add(MkProTools.createListProjectsTool(centralMemory));
        coordinatorTools.add(MkProTools.createListDirTool()); // Allow coordinator to list dirs too

        LlmAgent coordinatorAgent = LlmAgent.builder()
            .name("Coordinator")
            .description(coordDef.getDescription())
            .instruction(coordDef.getInstruction()
                    + contextInfo
                    + summaryContext)
            .model(model)
            .tools(coordinatorTools)
            .planning(true)
            .build();

        return buildRunner(coordinatorAgent, "mkpro");
    }

    private BaseTool createDelegationToolFromDef(String agentName, String toolName, 
                                                 Map<String, AgentConfig> agentConfigs, 
                                                 List<BaseTool> subAgentTools,
                                                 String contextInfo) {
        AgentDefinition def = agentDefinitions.get(agentName);
        if (def == null) {
            throw new RuntimeException("Definition not found for agent: " + agentName);
        }
        return createDelegationTool(toolName, def.getDescription(), agentName, BASE_AGENT_POLICY + "\n" + def.getInstruction(), agentConfigs, subAgentTools, contextInfo);
    }

    private Runner buildRunner(LlmAgent agent, String appName) {
        switch (runnerType) {
            case MAP_DB:
                try {
                    return MapDbRunner.builder()
                        .agent(agent)
                        .appName(appName)
                        .build();
                } catch (Exception e) {
                    System.err.println("Error creating MapDbRunner: " + e.getMessage());
                    // Fallback to InMemory
                }
            case POSTGRES:
                try {
                    return new PostgresRunner(  agent,   appName);
                } catch (Exception e) {
                    System.err.println("Error creating PostgresRunner: " + e.getMessage());
                    // Fallback to InMemory
                }
            case IN_MEMORY:
            default:
                return Runner.builder()
                    .agent(agent)
                    .appName(appName)
                    .sessionService(sessionService)
                    .artifactService(artifactService)
                    .memoryService(memoryService)
                    .build();
        }
    }

    private BaseLlm createModel(AgentConfig config) {
        if (config.getProvider() == Provider.GEMINI) {
            return new Gemini(config.getModelName(), apiKey);
        } else if (config.getProvider() == Provider.BEDROCK) {
            return new BedrockBaseLM(config.getModelName(), null);
        } else {
            return new OllamaBaseLM(config.getModelName(), "http://localhost:11434");
        }
    }

    private BaseTool createDelegationTool(String toolName, String description, String agentName, 
                                          String agentInstruction,
                                          Map<String, AgentConfig> agentConfigs, 
                                          List<BaseTool> subAgentTools,
                                          String contextInfo) {
        return new BaseTool(toolName, description) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "instruction", Schema.builder().type("STRING").description("Instructions for " + agentName + ".").build()
                                ))
                                .required(ImmutableList.of("instruction"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String instruction = (String) args.get("instruction");
                System.out.println(ANSI_BLUE + ">> Delegating to " + agentName + "..." + ANSI_RESET);
                AgentConfig config = agentConfigs.get(agentName);
                
                return Single.fromCallable(() -> {
                    String result = executeSubAgent(new AgentRequest(
                        agentName, 
                        agentInstruction + contextInfo,
                        config.getModelName(),
                        config.getProvider(),
                        instruction,
                        subAgentTools
                    ));
                    return Collections.singletonMap("result", result);
                });
            }
        };
    }

    private String executeSubAgent(AgentRequest request) {
        long startTime = System.currentTimeMillis();
        boolean success = true;
        StringBuilder output = new StringBuilder();
        
        // Log start of execution to persistent logs
        String executionInfo = String.format("Executing %s using %s (%s)", 
            request.getAgentName(), request.getProvider(), request.getModelName());
        logger.log("SYSTEM", executionInfo);

        try {
            AgentConfig config = new AgentConfig(request.getProvider(), request.getModelName());
            BaseLlm model = createModel(config);
            
            // Inject identity into state memory (instruction)
            String augmentedInstruction = request.getInstruction() + 
                "\n\n[System State: Running on Provider: " + request.getProvider() + 
                ", Model: " + request.getModelName() + "]" +
                "\n\nNOTE: You do not have direct access to action logs. If you need historical context or logs to complete a task, state this clearly in your final report so the Coordinator can provide it in the next turn.";

            LlmAgent subAgent = LlmAgent.builder()
                .name(request.getAgentName())
                .instruction(augmentedInstruction)
                .model(model)
                .tools(request.getTools())
                .planning(true)
                .build();

            // 1. Build the runner (which might create its own SessionService)
            Runner subRunner = buildRunner(subAgent, "mkpro");

            // 2. Use the runner's session service to create the session
            // This ensures the session exists in the correct store (InMemory, MapDB, Postgres)
            // Use agent name as the user name for better attribution
            Session subSession = subRunner.sessionService().createSession("mkpro", request.getAgentName()).blockingGet();

            Content content = Content.builder().role("user").parts(List.of(Part.fromText(request.getUserPrompt()))).build();
            
            subRunner.runAsync(request.getAgentName(), subSession.id(), content)
                  .filter(e -> e.content().isPresent())
                  .blockingForEach(e -> 
                      e.content().flatMap(Content::parts).orElse(Collections.emptyList())
                       .forEach(p -> p.text().ifPresent(output::append))
                  );
            
            return output.toString();
        } catch (Exception e) {
            success = false;
            return "Error executing sub-agent " + request.getAgentName() + ": " + e.getMessage();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            int inputLen = request.getUserPrompt().length();
            int outputLen = output.length();
            
            try {
                AgentStat stat = new AgentStat(
                    request.getAgentName(), 
                    request.getProvider().name(), 
                    request.getModelName(), 
                    duration, 
                    success, 
                    inputLen, 
                    outputLen
                );
                centralMemory.saveAgentStat(stat);
            } catch (Exception e) {
                System.err.println("Failed to save agent stats: " + e.getMessage());
            }
        }
    }
}
