package com.mkpro.vectorstore;

import com.google.adk.memory.MapDBVectorStore;
import com.google.adk.memory.Vector;

import java.util.List;

/**
 * Adapts MapDBVectorStore to SearchableVectorStore interface.
 * Allows using MapDB alongside InMemoryVectorStore without instanceof checks.
 */
public class MapDBVectorStoreAdapter implements SearchableVectorStore {

    private final MapDBVectorStore delegate;

    public MapDBVectorStoreAdapter(MapDBVectorStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public void insertVector(Vector vector) {
        delegate.insertVector(vector);
    }

    @Override
    public Vector getVector(String id) {
        return delegate.getVector(id);
    }

    @Override
    public void updateVector(Vector vector) {
        delegate.updateVector(vector);
    }

    @Override
    public void deleteVector(String id) {
        delegate.deleteVector(id);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public List<Vector> searchVectors(double[] queryVector, double threshold) {
        return delegate.searchVectors(queryVector, threshold);
    }

    @Override
    public List<Vector> searchTopNVectors(double[] queryVector, double threshold, int topN) {
        return delegate.searchTopNVectors(queryVector, threshold, topN);
    }
}
