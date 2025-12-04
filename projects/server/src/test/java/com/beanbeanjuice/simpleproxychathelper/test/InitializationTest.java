package com.beanbeanjuice.simpleproxychathelper.test;

import com.beanbeanjuice.simpleproxychathelper.SimpleProxyChatHelper;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class InitializationTest {

    private ServerMock server;
    private SimpleProxyChatHelper plugin;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(SimpleProxyChatHelper.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @org.junit.jupiter.api.Disabled("MockBukkit MaterialTags initialization bug prevents player creation")
    @DisplayName("Confirm MockBukkit is correctly working.")
    public void testInitialization() {
        PlayerMock playerMock = server.addPlayer();
        Assertions.assertEquals(1, server.getOnlinePlayers().size());
        
        // Note: This test is disabled due to MockBukkit MaterialTags initialization issues
        // The player cannot be created due to framework limitations
        Assertions.assertNotNull(playerMock);
        Assertions.assertEquals(server, playerMock.getServer());
    }

    @Test
    @DisplayName("Confirm MockBukkit Server is the Same As Plugin Server")
    public void testServerIsEqual() {
        Assertions.assertEquals(plugin.getServer(), server);
    }
}
