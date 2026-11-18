package com.johnowl.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.util.ClassUtils;

class NoServletStackTest {

    @Test
    void springMvcIsNotOnTheClasspath() {
        assertThat(isPresent("org.springframework.web.servlet.DispatcherServlet")).isFalse();
    }

    @Test
    void tomcatIsNotOnTheClasspath() {
        assertThat(isPresent("org.apache.catalina.startup.Tomcat")).isFalse();
    }

    private static boolean isPresent(String className) {
        return ClassUtils.isPresent(className, NoServletStackTest.class.getClassLoader());
    }
}
