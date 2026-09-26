package com.ngumn.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Boot only auto-serves index.html as a "welcome page" at the
 * site root ("/"). The admin dashboard lives under /admin/ as a plain
 * static page (src/main/resources/static/admin/index.html), so without
 * this, requesting "/admin" or "/admin/" 404s even though the file is
 * there - only "/admin/index.html" would resolve. This forwards both
 * to the actual file.
 */
@Configuration
public class WebUiConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/admin").setViewName("forward:/admin/index.html");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }
}
