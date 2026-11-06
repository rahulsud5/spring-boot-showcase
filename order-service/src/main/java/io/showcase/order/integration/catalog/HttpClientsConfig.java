package io.showcase.order.integration.catalog;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * Registers HTTP service interfaces in the Spring Framework 7 HTTP Service Registry.
 *
 * <p>Clients are organized in GROUPS. Each group shares one configured RestClient (base URL,
 * timeouts, interceptors), set in application.yml under {@code spring.http.serviceclient.<group>.*}.
 * More interfaces for the same backend simply join the group.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "catalog", types = CatalogClient.class)
class HttpClientsConfig {
}
