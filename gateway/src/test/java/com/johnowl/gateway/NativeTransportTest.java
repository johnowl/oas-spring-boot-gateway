package com.johnowl.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class NativeTransportTest {

    @Test
    @EnabledOnOs(OS.LINUX)
    void epollIsAvailableOnLinux() {
        assertThat(Epoll.isAvailable())
                .as("epoll unavailable: %s", Epoll.unavailabilityCause())
                .isTrue();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void kqueueIsAvailableOnMacOs() {
        assertThat(KQueue.isAvailable())
                .as("kqueue unavailable: %s", KQueue.unavailabilityCause())
                .isTrue();
    }
}
