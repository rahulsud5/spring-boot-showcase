package io.showcase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Full-context test of the authorization server: issue a real JWT via the client-credentials grant
 * and inspect its claims. It needs no external infrastructure, so it runs as a regular unit test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthServerTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JsonMapper json;

    @Test
    void publishesOpenIdDiscoveryAndJwks() {
        MvcTestResult discovery = mvc.get().uri("/.well-known/openid-configuration").exchange();
        assertThat(discovery).hasStatusOk();
        assertThat(discovery).bodyJson().extractingPath("$.issuer").isEqualTo("http://localhost:9000");

        assertThat(mvc.get().uri("/oauth2/jwks")).hasStatusOk().bodyJson().extractingPath("$.keys[0].kty").isEqualTo("RSA");
    }

    @Test
    void clientCredentialsTokenCarriesRolesAudienceAndScopes() throws Exception {
        MvcTestResult result = mvc.post().uri("/oauth2/token")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("machine-client:machine-secret".getBytes(StandardCharsets.UTF_8)))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("grant_type=client_credentials&scope=orders.read")
                .exchange();

        assertThat(result).hasStatusOk();
        String accessToken = json.readTree(result.getResponse().getContentAsString()).get("access_token").asString();

        // A JWT is header.payload.signature, each part base64url-encoded. Decode the payload to inspect the claims.
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
        assertThat(claims.get("sub").asString()).isEqualTo("machine-client");
        assertThat(claims.get("roles").get(0).asString()).isEqualTo("SERVICE");
        assertThat(claims.get("aud").toString()).contains("order-service");
        assertThat(claims.get("scope").toString()).contains("orders.read");
    }

    @Test
    void wrongClientSecretIsRejected() {
        assertThat(mvc.post().uri("/oauth2/token")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("machine-client:wrong".getBytes(StandardCharsets.UTF_8)))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("grant_type=client_credentials"))
                .hasStatus(401);
    }
}
