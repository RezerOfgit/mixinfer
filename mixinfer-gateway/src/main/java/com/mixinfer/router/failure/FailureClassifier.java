package com.mixinfer.router.failure;

/**
 * Classifies a thrown error into a {@link FailureType} for routing decisions.
 */
public interface FailureClassifier {

    FailureType classify(Throwable error);
}