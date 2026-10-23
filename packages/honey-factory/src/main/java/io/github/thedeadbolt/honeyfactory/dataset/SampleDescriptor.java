package io.github.thedeadbolt.honeyfactory.dataset;

import io.github.thedeadbolt.honeyfactory.contract.Split;

/** Identity and dataset-relative image paths of one base sample. */
public record SampleDescriptor(String sampleId, String sceneId, Split split, int viewpointIndex, String rgbPath, String maskPath) {
}
