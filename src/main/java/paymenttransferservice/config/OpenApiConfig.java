package paymenttransferservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentTransferServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Payment Transfer Service")
                .description("Basic payment transfer API between accounts on the same platform")
                .version("v1"));
    }
}
