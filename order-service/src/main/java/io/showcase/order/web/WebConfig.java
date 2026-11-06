package io.showcase.order.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC customization.
 *
 * <h2>API VERSIONING, a first-class feature in Spring Framework 7</h2>
 * Before Spring 7, versioning meant URL prefixes (/v1/...), custom headers checked by hand, or
 * third-party libraries. Now:
 * <ol>
 *   <li>configure HOW the version is resolved: a request header here; alternatives are a query
 *       parameter, a path segment or the media type ({@code application/json;v=2});</li>
 *   <li>declare versions on mappings: {@code @GetMapping(path = "/{id}", version = "2")};</li>
 *   <li>unsupported versions are rejected with 400; a missing header falls back to the default.</li>
 * </ol>
 * Clients ({@code RestClient}, {@code WebClient}, the HTTP interface clients) get a matching
 * {@code ApiVersionInserter}, so the version is set on outgoing calls consistently. Version
 * "1.1+" syntax means "1.1 and later" (baseline versions), so one handler can serve several versions.
 *
 * <h2>Stable page serialization</h2>
 * {@code VIA_DTO} serializes {@code Page} responses through Spring Data's {@code PagedModel} DTO:
 * a stable JSON contract instead of PageImpl's internal structure.
 */
@Configuration(proxyBeanMethods = false)
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
class WebConfig implements WebMvcConfigurer {

    static final String API_VERSION_HEADER = "X-API-Version";

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer.useRequestHeader(API_VERSION_HEADER)
                .addSupportedVersions("1", "2")
                .setDefaultVersion("1");
    }
}
