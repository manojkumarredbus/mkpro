package com.mkpro.vectorstore;

import com.google.adk.memory.Vector;
import com.google.adk.memory.VectorStore;

import java.util.List;

/**
 * VectorStore with top-N similarity search. Implementations can use optimized
 * storage (MapDB, in-memory) without exposing implementation details.
 */
public interface SearchableVectorStore extends VectorStore {

    /**
     * Returns the top N vectors by cosine similarity above the threshold.
     */
    List<Vector> searchTopNVectors(double[] queryVector, double threshold, int topN);
}
