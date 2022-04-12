package uk.gov.di.authentication.oidc.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.nimbusds.jwt.JWTClaimsSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.authentication.oidc.entity.BackChannelLogoutMessage;
import uk.gov.di.authentication.oidc.services.HttpRequestService;
import uk.gov.di.authentication.shared.helpers.ObjectMapperFactory;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.KmsConnectionService;
import uk.gov.di.authentication.shared.services.TokenService;

import java.net.URI;
import java.sql.Date;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import static java.util.Collections.emptyMap;

public class BackChannelLogoutRequestHandler implements RequestHandler<SQSEvent, Object> {

    private static final Logger LOG = LogManager.getLogger(BackChannelLogoutRequestHandler.class);
    private final ConfigurationService instance;
    private final HttpRequestService httpRequestService;
    private final TokenService tokenService;
    private final Clock clock;

    public BackChannelLogoutRequestHandler() {
        this.instance = ConfigurationService.getInstance();
        this.httpRequestService = new HttpRequestService();
        this.tokenService = new TokenService(instance, null, new KmsConnectionService(instance));
        this.clock = Clock.systemUTC();
    }

    public BackChannelLogoutRequestHandler(
            ConfigurationService configurationService,
            HttpRequestService httpRequestService,
            TokenService tokenService,
            Clock clock) {
        this.instance = configurationService;
        this.httpRequestService = httpRequestService;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    @Override
    public Object handleRequest(SQSEvent event, Context context) {

        event.getRecords().forEach(this::sendLogoutMessage);

        return null;
    }

    private void sendLogoutMessage(SQSMessage record) {
        LOG.info("Handling backchannel logout request with id: {}", record.getMessageId());

        try {
            var payload =
                    ObjectMapperFactory.getInstance()
                            .readValue(record.getBody(), BackChannelLogoutMessage.class);

            var claims = generateClaims(payload);

            var body = tokenService.generateSignedJWT(claims).toString();

            httpRequestService.post(URI.create(payload.getLogoutUri()), body);

        } catch (JsonProcessingException e) {

        }
    }

    public JWTClaimsSet generateClaims(BackChannelLogoutMessage inputEvent) {
        return new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .audience(inputEvent.getClientId())
                .subject(inputEvent.getSubjectId())
                .issuer(instance.getOidcApiBaseURL().orElseThrow())
                .issueTime(Date.from(clock.instant()))
                .claim(
                        "events",
                        Map.of("http://schemas.openid.net/event/backchannel-logout", emptyMap()))
                .build();
    }
}
