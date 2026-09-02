package com.mkpro.vectorstore;

import com.google.adk.memory.Vector;
import com.google.adk.memory.VectorStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation for IN_MEMORY runner mode.
 * No file locks; index is ephemeral and lost on restart.
 */
public class InMemoryVectorStore implements SearchableVectorStore {

    private final Map<String, Vector> vectorMap = new ConcurrentHashMap<>();

    @Override
    public void insertVector(Vector vector) {
        vectorMap.put(vector.getId(), vector);
    }

    @Override
    public Vector getVector(String id) {
        return vectorMap.get(id);
    }

    @Override
    public void updateVector(Vector vector) {
        vectorMap.put(vector.getId(), vector);
    }

    @Override
    public void deleteVector(String id) {
        vectorMap.remove(id);
    }

    @Override
    public void close() {
        vectorMap.clear();
    }

    private double cosineSimilarity(double[] vectorA, double[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dotProduct / denom;
    }

    @Override
    public List<Vector> searchVectors(double[] queryVector, double threshold) {
        List<Vector> result = new ArrayList<>();
        for (Vector vector : vectorMap.values()) {
            double similarity = cosineSimilarity(vector.getEmbedding(), queryVector);
            if (similarity >= threshold) {
                vector.getMetadata().put("score", similarity);
                result.add(vector);
            }
        }
        return result;
    }

    @Override
    public List<Vector> searchTopNVectors(double[] queryVector, double threshold, int topN) {
        PriorityQueue<Vector> topVectors = new PriorityQueue<>(
                Math.max(1, topN),
                Comparator.comparingDouble(v -> (double) v.getMetadata().getOrDefault("score", 0.0)));

        for (Vector vector : vectorMap.values()) {
            double similarity = cosineSimilarity(vector.getEmbedding(), queryVector);
            if (similarity >= threshold) {
                vector.getMetadata().put("score", similarity);
                if (topVectors.size() < topN) {
                    topVectors.offer(vector);
                } else if (similarity > (double) topVectors.peek().getMetadata().get("score")) {
                    topVectors.poll();
                    topVectors.offer(vector);
                }
            }
        }

        List<Vector> result = new ArrayList<>(topVectors);
        result.sort((v1, v2) ->
                Double.compare(
                        (double) v2.getMetadata().get("score"),
                        (double) v1.getMetadata().get("score")));
        return result;
    }
}
