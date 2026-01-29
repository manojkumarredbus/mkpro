package com.mkpro.models;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Centralized model configuration for different providers.
 * This class provides default models and available model lists for each provider,
 * making it easy to switch between providers and models generically.
 */
public class ModelConfiguration {
    
    // Default models per provider
    public static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";
    public static final String DEFAULT_OLLAMA_MODEL = "devstral-small-2";
    public static final String DEFAULT_BEDROCK_MODEL = "anthropic.claude-3-sonnet-20240229-v1:0";
    
    // Available Gemini models
    private static final List<String> GEMINI_MODELS = Arrays.asList(
        "gemini-3-pro-preview",
        "gemini-3-flash-preview",
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash-thinking",
        "gemini-2.0-pro",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
        "gemini-2.0-flash-thinking-exp",
        "gemini-1.5-pro",
        "gemini-1.5-flash",
        "gemini-1.5-flash-8b"
    );
    
    // Available Bedrock models
    private static final List<String> BEDROCK_MODELS = Arrays.asList(
        "anthropic.claude-3-sonnet-20240229-v1:0",
        "anthropic.claude-3-haiku-20240307-v1:0",
        "anthropic.claude-3-5-sonnet-20240620-v1:0",
        "meta.llama3-70b-instruct-v1:0",
        "meta.llama3-8b-instruct-v1:0",
        "amazon.titan-text-express-v1"
    );
    
    /**
     * Gets the default model name for a given provider.
     * 
     * @param provider The provider enum
     * @return The default model name for the provider
     */
    public static String getDefaultModel(Provider provider) {
        switch (provider) {
            case GEMINI:
                return DEFAULT_GEMINI_MODEL;
            case OLLAMA:
                return DEFAULT_OLLAMA_MODEL;
            case BEDROCK:
                return DEFAULT_BEDROCK_MODEL;
            default:
                return DEFAULT_OLLAMA_MODEL;
        }
    }
    
    /**
     * Gets the list of available models for a given provider.
     * For OLLAMA, this returns an empty list as models are fetched dynamically.
     * 
     * @param provider The provider enum
     * @return An unmodifiable list of available model names
     */
    public static List<String> getAvailableModels(Provider provider) {
        switch (provider) {
            case GEMINI:
                return Collections.unmodifiableList(GEMINI_MODELS);
            case BEDROCK:
                return Collections.unmodifiableList(BEDROCK_MODELS);
            case OLLAMA:
                // Ollama models are fetched dynamically from the local server
                return Collections.emptyList();
            default:
                return Collections.emptyList();
        }
    }
    
    /**
     * Checks if a model name is valid for the given provider.
     * For OLLAMA, this always returns true as models are fetched dynamically.
     * 
     * @param provider The provider enum
     * @param modelName The model name to validate
     * @return true if the model is valid (or provider is OLLAMA), false otherwise
     */
    public static boolean isValidModel(Provider provider, String modelName) {
        if (provider == Provider.OLLAMA) {
            // Ollama models are validated dynamically
            return true;
        }
        List<String> availableModels = getAvailableModels(provider);
        return availableModels.contains(modelName);
    }
}
