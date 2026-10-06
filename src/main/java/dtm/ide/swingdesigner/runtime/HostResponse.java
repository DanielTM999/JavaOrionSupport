package dtm.ide.swingdesigner.runtime;

import com.fasterxml.jackson.databind.JsonNode;

public record HostResponse(JsonNode result, byte[] blob) {

    public boolean hasBlob() {
        return blob != null && blob.length > 0;
    }
}
