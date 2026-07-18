package com.cryptopal.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the API for Swagger UI.
 *
 * <p>springdoc already discovers the endpoints by scanning the controllers. What it
 * cannot infer is the intent, so this class supplies the part a reader actually needs:
 * what the application is, and the one rule about order amounts that is easy to get
 * wrong from the endpoint signature alone.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cryptoPalOpenApi() {
        return new OpenAPI().info(new Info()
                .title("LumpaCrypto Core API")
                .version("0.1.0")
                .description("""
                        Simulated cryptocurrency trading and insight platform.

                        Prices are real, sourced from the Binance Spot API, but no order \
                        placed here ever reaches an exchange. Every trade is simulated \
                        against a virtual balance, and Binance is used only as a price feed.

                        ## The order amount rule

                        `POST /api/orders` takes a single `amount` field whose meaning \
                        depends on `side`, so read this before calling it:

                        - **BUY**: `amount` is the **fiat to spend**, in virtual USD. \
                        Sending `100` means "spend 100 dollars", and the quantity of crypto \
                        received is worked out from the current price.
                        - **SELL**: `amount` is the **crypto quantity to sell**. Sending \
                        `0.5` for BTC means "sell half a bitcoin", and the fiat received is \
                        worked out from the current price.

                        The two are not interchangeable. Sending a fiat amount to a SELL \
                        would try to sell 100 whole coins.

                        ## The assistant has a quota

                        `POST /api/ai/ask` is the only endpoint here with a limit, because \
                        Gemini's capacity is the only thing this application spends that it \
                        does not own. **20 questions per account, and 300 across everyone**, \
                        in a rolling 24 hours. Crossing either answers `RATE_LIMITED` (429), \
                        and the message says which limit it was.

                        Nothing else is rate limited, and nothing else stops working when \
                        the assistant runs out.
                        """));
    }
}
