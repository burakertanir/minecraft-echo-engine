package com.audiophilecraft.sound;

import java.util.List;
import java.util.Objects;

/** Combined legacy profile plus the individual profiles calculated in the same scan pass. */
public final class AcousticSceneScanResult {
    private final AcousticScanResult combinedResult;
    private final List<AcousticProfile> groupProfiles;

    AcousticSceneScanResult(AcousticScanResult combinedResult, List<AcousticProfile> groupProfiles) {
        this.combinedResult = Objects.requireNonNull(combinedResult, "combinedResult");
        this.groupProfiles = List.copyOf(groupProfiles);
    }

    public AcousticScanResult combinedResult() {
        return combinedResult;
    }

    public List<AcousticProfile> groupProfiles() {
        return groupProfiles;
    }
}
