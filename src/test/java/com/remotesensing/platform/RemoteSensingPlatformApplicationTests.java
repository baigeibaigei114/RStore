package com.remotesensing.platform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RemoteSensingPlatformApplicationTests {

    @Test
    void applicationClassCanBeLoaded() {
        assertDoesNotThrow(() -> Class.forName(RemoteSensingPlatformApplication.class.getName()));
    }
}
