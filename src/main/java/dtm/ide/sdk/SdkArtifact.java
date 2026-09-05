package dtm.ide.sdk;

public record SdkArtifact(String fileName, String url, String progressId, String displayName) {

    public SdkArtifact {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url");
        }
        progressId = progressId == null || progressId.isBlank() ? "downloadSdk" : progressId;
        displayName = displayName == null || displayName.isBlank() ? fileName : displayName;
    }
}
