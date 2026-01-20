package com.mkpro;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.io.File;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class CentralMemory {

    private final String dbPath;

    public CentralMemory() {
        String userHome = System.getProperty("user.home");
        File mkproDir = new File(userHome, ".mkpro");
        if (!mkproDir.exists()) {
            mkproDir.mkdirs();
        }
        this.dbPath = new File(mkproDir, "central_memory.db").getAbsolutePath();
    }

    private DB openDB() {
        return DBMaker.fileDB(dbPath)
                .transactionEnable()
                .make();
    }

    public void saveMemory(String projectPath, String content) {
        try (DB db = openDB()) {
            HTreeMap<String, String> projectMemories = db.hashMap("project_memories")
                    .keySerializer(Serializer.STRING)
                    .valueSerializer(Serializer.STRING)
                    .createOrOpen();

            // Append timestamp
            String timestampedContent = String.format("--- Saved: %s ---\n%s", Instant.now(), content);
            
            String existing = projectMemories.get(projectPath);
            if (existing != null) {
                timestampedContent = existing + "\n\n" + timestampedContent;
            }
            
            projectMemories.put(projectPath, timestampedContent);
            db.commit();
        }
    }

    public String getMemory(String projectPath) {
        try (DB db = openDB()) {
            HTreeMap<String, String> projectMemories = db.hashMap("project_memories")
                    .keySerializer(Serializer.STRING)
                    .valueSerializer(Serializer.STRING)
                    .createOrOpen();
            return projectMemories.get(projectPath);
        }
    }
    
    public Map<String, String> getAllMemories() {
        try (DB db = openDB()) {
            HTreeMap<String, String> projectMemories = db.hashMap("project_memories")
                    .keySerializer(Serializer.STRING)
                    .valueSerializer(Serializer.STRING)
                    .createOrOpen();
            Map<String, String> copy = new HashMap<>();
            projectMemories.forEach((k, v) -> copy.put((String)k, (String)v));
            return copy;
        }
    }

    public void saveAgentConfig(String agentName, String provider, String modelName) {
        try (DB db = openDB()) {
            HTreeMap<String, String> configs = db.hashMap("agent_configs")
                    .keySerializer(Serializer.STRING)
                    .valueSerializer(Serializer.STRING)
                    .createOrOpen();
            // Format: PROVIDER|MODEL
            configs.put(agentName, provider + "|" + modelName);
            db.commit();
        }
    }

    public Map<String, String> getAgentConfigs() {
        try (DB db = openDB()) {
            HTreeMap<String, String> configs = db.hashMap("agent_configs")
                    .keySerializer(Serializer.STRING)
                    .valueSerializer(Serializer.STRING)
                    .createOrOpen();
            Map<String, String> copy = new HashMap<>();
            configs.forEach((k, v) -> copy.put((String)k, (String)v));
            return copy;
        }
    }
}
