package com.mkpro.vectorstore;

import com.google.adk.memory.MapDBVectorStore;
import com.mkpro.models.RunnerType;

import java.io.File;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized provider for vector stores. Handles creation, caching, lock fallback,
 * and runner-type-based selection in one place.
 */
public final class VectorStoreProvider {

    private static final String ANSI_BLUE = "\u001b[34m";
    private static final Map<String, MapDBVectorStore> persistentCache = new ConcurrentHashMap<>();

    private VectorStoreProvider() {}

    /**
     * Returns a SearchableVectorStore for the given project.
     * <ul>
     *   <li>IN_MEMORY: Ephemeral in-memory store (no file locks)</li>
     *   <li>MAP_DB/POSTGRES: File-based store at ~/.mkpro/vectors/{project}.db</li>
     *   <li>On file lock conflict: Falls back to in-memory with a warning</li>
     * </ul>
     */
    public static SearchableVectorStore getOrCreate(String projectName, RunnerType runnerType) {
        if (runnerType == RunnerType.IN_MEMORY) {
            return new InMemoryVectorStore();
        }
        return getOrCreatePersistent(projectName);
    }

    private static SearchableVectorStore getOrCreatePersistent(String projectName) {
        try {
            MapDBVectorStore db = persistentCache.computeIfAbsent(projectName, VectorStoreProvider::createMapDBStore);
            return new MapDBVectorStoreAdapter(db);
        } catch (Exception e) {
            if (isLockError(e)) {
                System.err.println(ANSI_BLUE + "Vector store " + projectName + ".db is locked by another process. "
                        + "Using in-memory store (index will not persist). Close other mkpro instances or use IN_MEMORY runner.\u001b[0m");
                return new InMemoryVectorStore();
            }
            throw e;
        }
    }

    private static MapDBVectorStore createMapDBStore(String projectName) {
        String path = Paths.get(System.getProperty("user.home"), ".mkpro", "vectors", projectName + ".db").toString();
        File parent = new File(path).getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        return new MapDBVectorStore(path, projectName);
    }

    private static boolean isLockError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("locked") || lower.contains("already opened");
    }

    /**
     * For multi-project search: opens each project's persistent store. Returns SearchableVectorStore
     * or null if the project's store is locked (caller should skip that project).
     */
    public static SearchableVectorStore tryOpenForSearch(String projectName) {
        try {
            MapDBVectorStore db = persistentCache.computeIfAbsent(projectName, VectorStoreProvider::createMapDBStore);
            return new MapDBVectorStoreAdapter(db);
        } catch (Exception e) {
            if (isLockError(e)) {
                return null; // Caller skips this project
            }
            throw e;
        }
    }
}
