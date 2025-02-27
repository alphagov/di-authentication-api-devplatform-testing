package uk.gov.di.authentication.ipv.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.OIDCClaimsRequest;
import com.nimbusds.openid.connect.sdk.claims.ClaimsSetRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.authentication.ipv.domain.IPVAuditableEvent;
import uk.gov.di.authentication.ipv.entity.IPVAuthorisationRequest;
import uk.gov.di.authentication.ipv.entity.IPVAuthorisationResponse;
import uk.gov.di.authentication.ipv.services.IPVAuthorisationService;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.UserProfile;
import uk.gov.di.authentication.shared.helpers.ClientSubjectHelper;
import uk.gov.di.authentication.shared.helpers.IpAddressHelper;
import uk.gov.di.authentication.shared.helpers.PersistentIdHelper;
import uk.gov.di.authentication.shared.lambda.BaseFrontendHandler;
import uk.gov.di.authentication.shared.serialization.Json.JsonException;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.ClientService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.CloudwatchMetricsService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.KmsConnectionService;
import uk.gov.di.authentication.shared.services.NoSessionOrchestrationService;
import uk.gov.di.authentication.shared.services.RedisConnectionService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.state.UserContext;

import java.util.Map;
import java.util.Optional;

import static uk.gov.di.authentication.shared.domain.RequestHeaders.CLIENT_SESSION_ID_HEADER;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyResponse;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.LogFieldName.CLIENT_ID;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.UNKNOWN;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.attachLogFieldToLogs;
import static uk.gov.di.authentication.shared.helpers.RequestHeaderHelper.getHeaderValueFromHeaders;
import static uk.gov.di.authentication.shared.services.AuditService.MetadataPair.pair;

public class IPVAuthorisationHandler extends BaseFrontendHandler<IPVAuthorisationRequest>
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOG = LogManager.getLogger(IPVAuthorisationHandler.class);

    private final AuditService auditService;
    private final IPVAuthorisationService authorisationService;
    private final NoSessionOrchestrationService noSessionOrchestrationService;
    private final CloudwatchMetricsService cloudwatchMetricsService;

    public IPVAuthorisationHandler(
            ConfigurationService configurationService,
            SessionService sessionService,
            ClientSessionService clientSessionService,
            ClientService clientService,
            AuthenticationService authenticationService,
            AuditService auditService,
            IPVAuthorisationService authorisationService,
            NoSessionOrchestrationService noSessionOrchestrationService,
            CloudwatchMetricsService cloudwatchMetricsService) {
        super(
                IPVAuthorisationRequest.class,
                configurationService,
                sessionService,
                clientSessionService,
                clientService,
                authenticationService);
        this.auditService = auditService;
        this.authorisationService = authorisationService;
        this.noSessionOrchestrationService = noSessionOrchestrationService;
        this.cloudwatchMetricsService = cloudwatchMetricsService;
    }

    public IPVAuthorisationHandler() {
        this(ConfigurationService.getInstance());
    }

    public IPVAuthorisationHandler(ConfigurationService configurationService) {
        super(IPVAuthorisationRequest.class, configurationService);
        this.auditService = new AuditService(configurationService);
        this.authorisationService =
                new IPVAuthorisationService(
                        configurationService,
                        new RedisConnectionService(configurationService),
                        new KmsConnectionService(configurationService));
        this.noSessionOrchestrationService =
                new NoSessionOrchestrationService(configurationService);
        this.cloudwatchMetricsService = new CloudwatchMetricsService(configurationService);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequestWithUserContext(
            APIGatewayProxyRequestEvent input,
            Context context,
            IPVAuthorisationRequest request,
            UserContext userContext) {
        try {
            if (!configurationService.isIdentityEnabled()) {
                LOG.error("Identity is not enabled");
                throw new RuntimeException("Identity is not enabled");
            }
            var persistentId =
                    PersistentIdHelper.extractPersistentIdFromHeaders(input.getHeaders());
            var rpClientID = userContext.getClient().map(ClientRegistry::getClientID);
            attachLogFieldToLogs(CLIENT_ID, rpClientID.orElse(UNKNOWN));
            LOG.info("IPVAuthorisationHandler received request");
            var authRequest =
                    AuthenticationRequest.parse(
                            userContext.getClientSession().getAuthRequestParams());
            var pairwiseSubject =
                    ClientSubjectHelper.getSubjectWithSectorIdentifier(
                            userContext.getUserProfile().orElseThrow(),
                            configurationService.getInternalSectorUri(),
                            authenticationService);
            var state = new State();
            var claimsSetRequest =
                    buildIpvClaimsRequest(authRequest)
                            .map(ClaimsSetRequest::toJSONString)
                            .orElse(null);

            var clientSessionId =
                    getHeaderValueFromHeaders(
                            input.getHeaders(),
                            CLIENT_SESSION_ID_HEADER,
                            configurationService.getHeadersCaseInsensitive());

            var encryptedJWT =
                    authorisationService.constructRequestJWT(
                            state,
                            authRequest.getScope(),
                            pairwiseSubject,
                            claimsSetRequest,
                            Optional.ofNullable(clientSessionId).orElse("unknown"),
                            userContext.getUserProfile().map(UserProfile::getEmail).orElseThrow());
            var authRequestBuilder =
                    new AuthorizationRequest.Builder(
                                    new ResponseType(ResponseType.Value.CODE),
                                    new ClientID(
                                            configurationService.getIPVAuthorisationClientId()))
                            .endpointURI(configurationService.getIPVAuthorisationURI())
                            .requestObject(encryptedJWT);

            var ipvAuthorisationRequest = authRequestBuilder.build();
            authorisationService.storeState(userContext.getSession().getSessionId(), state);
            noSessionOrchestrationService.storeClientSessionIdAgainstState(clientSessionId, state);
            auditService.submitAuditEvent(
                    IPVAuditableEvent.IPV_AUTHORISATION_REQUESTED,
                    clientSessionId,
                    userContext.getSession().getSessionId(),
                    rpClientID.orElse(AuditService.UNKNOWN),
                    userContext.getSession().getInternalCommonSubjectIdentifier(),
                    request.getEmail(),
                    IpAddressHelper.extractIpAddress(input),
                    AuditService.UNKNOWN,
                    persistentId,
                    pair(
                            "clientLandingPageUrl",
                            userContext
                                    .getClient()
                                    .map(ClientRegistry::getLandingPageUrl)
                                    .orElse(AuditService.UNKNOWN)));

            LOG.info(
                    "IPVAuthorisationHandler successfully processed request, redirect URI {}",
                    ipvAuthorisationRequest.toURI().toString());
            cloudwatchMetricsService.incrementCounter(
                    "IPVHandoff", Map.of("Environment", configurationService.getEnvironment()));
            return generateApiGatewayProxyResponse(
                    200, new IPVAuthorisationResponse(ipvAuthorisationRequest.toURI().toString()));

        } catch (ParseException | JsonException e) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1001);
        }
    }

    private Optional<ClaimsSetRequest> buildIpvClaimsRequest(AuthenticationRequest authRequest) {
        return Optional.ofNullable(authRequest)
                .map(AuthenticationRequest::getOIDCClaims)
                .map(OIDCClaimsRequest::getUserInfoClaimsRequest);
    }
}
