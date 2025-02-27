package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.id.Subject;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent;
import uk.gov.di.authentication.frontendapi.entity.ClientStartInfo;
import uk.gov.di.authentication.frontendapi.entity.Features;
import uk.gov.di.authentication.frontendapi.entity.StartResponse;
import uk.gov.di.authentication.frontendapi.entity.UserStartInfo;
import uk.gov.di.authentication.frontendapi.services.StartService;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ClientSession;
import uk.gov.di.authentication.shared.entity.CustomScopeValue;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.ServiceType;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.entity.VectorOfTrust;
import uk.gov.di.authentication.shared.helpers.PersistentIdHelper;
import uk.gov.di.authentication.shared.serialization.Json;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.SerializationService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.state.UserContext;
import uk.gov.di.authentication.sharedtest.helper.KeyPairHelper;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.di.authentication.shared.services.AuditService.MetadataPair.pair;
import static uk.gov.di.authentication.sharedtest.helper.RequestEventHelper.contextWithSourceIp;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasBody;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;

class StartHandlerTest {

    public static final String TEST_CLIENT_ID = "test_client_id";
    public static final String TEST_CLIENT_NAME = "test_client_name";
    public static final String CLIENT_SESSION_ID_HEADER = "Client-Session-Id";
    public static final String SESSION_ID_HEADER = "Session-Id";
    public static final String CLIENT_SESSION_ID = "known-client-session-id";
    public static final String SESSION_ID = "some-session-id";
    public static final State STATE = new State();
    public static final String PERSISTENT_ID = "some-persistent-id-value";
    public static final URI REDIRECT_URL = URI.create("https://localhost/redirect");
    private static final Scope DOC_APP_SCOPE =
            new Scope(OIDCScopeValue.OPENID, CustomScopeValue.DOC_CHECKING_APP);
    private static final Nonce NONCE = new Nonce();
    private static final ClientID CLIENT_ID = new ClientID("test-id");
    private static final String AUDIENCE = "https://localhost/authorize";
    private static final Json objectMapper = SerializationService.getInstance();

    private StartHandler handler;
    private final Context context = mock(Context.class);
    private final ClientSessionService clientSessionService = mock(ClientSessionService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final StartService startService = mock(StartService.class);
    private final UserContext userContext = mock(UserContext.class);
    private final ClientRegistry clientRegistry = mock(ClientRegistry.class);
    private final ConfigurationService configurationService = mock(ConfigurationService.class);
    private final Session session = new Session(SESSION_ID);
    private final ClientSession clientSession = getClientSession();
    private final ClientSession docAppClientSession = getDocAppClientSession();

    @BeforeEach
    void beforeEach() {
        when(configurationService.isIdentityEnabled()).thenReturn(true);
        when(context.getAwsRequestId()).thenReturn("aws-session-id");
        when(sessionService.getSessionFromRequestHeaders(any()))
                .thenReturn(Optional.of(new Session("session-id")));
        when(userContext.getClient()).thenReturn(Optional.of(clientRegistry));
        when(userContext.getClientSession()).thenReturn(clientSession);
        when(clientRegistry.getClientID()).thenReturn(TEST_CLIENT_ID);
        handler =
                new StartHandler(
                        clientSessionService,
                        sessionService,
                        auditService,
                        startService,
                        configurationService);
    }

    private static Stream<Arguments> cookieConsentGaTrackingIdValues() {
        return Stream.of(
                Arguments.of(null, "some-ga-tracking-id", true),
                Arguments.of("some-cookie-consent-value", null, true),
                Arguments.of(null, null, true),
                Arguments.of("some-cookie-consent-value", "some-ga-tracking-id", true),
                Arguments.of(null, "some-ga-tracking-id", false),
                Arguments.of("some-cookie-consent-value", null, false),
                Arguments.of(null, null, false),
                Arguments.of("some-cookie-consent-value", "some-ga-tracking-id", false));
    }

    @ParameterizedTest
    @MethodSource("cookieConsentGaTrackingIdValues")
    void shouldReturn200WithStartResponse(
            String cookieConsentValue, String gaTrackingId, Boolean isFeaturesEnabled)
            throws ParseException, Json.JsonException {
        var userStartInfo = getUserStartInfo(cookieConsentValue, gaTrackingId);
        when(startService.validateSession(session, CLIENT_SESSION_ID)).thenReturn(session);
        when(startService.buildUserContext(session, clientSession)).thenReturn(userContext);
        when(startService.buildClientStartInfo(userContext)).thenReturn(getClientStartInfo());
        when(startService.getGATrackingId(anyMap())).thenReturn(gaTrackingId);
        when(startService.getCookieConsentValue(anyMap(), anyString()))
                .thenReturn(cookieConsentValue);
        when(startService.buildUserStartInfo(userContext, cookieConsentValue, gaTrackingId, true))
                .thenReturn(userStartInfo);
        Features features = new Features();
        features.setUpdatePasswordHintTextVersion("1");
        when(startService.getSessionFeatures()).thenReturn(features);
        when(configurationService.isExtendedFeatureFlagsEnabled()).thenReturn(isFeaturesEnabled);
        usingValidClientSession();
        usingValidSession();

        Map<String, String> headers = new HashMap<>();
        headers.put(PersistentIdHelper.PERSISTENT_ID_HEADER_NAME, PERSISTENT_ID);
        headers.put(CLIENT_SESSION_ID_HEADER, CLIENT_SESSION_ID);
        headers.put(SESSION_ID_HEADER, SESSION_ID);
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(headers);
        event.setRequestContext(contextWithSourceIp("123.123.123.123"));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(200));

        StartResponse response = objectMapper.readValue(result.getBody(), StartResponse.class);

        assertThat(
                response.getClient().getClientName(),
                equalTo(getClientStartInfo().getClientName()));
        assertThat(response.getClient().getScopes(), equalTo(getClientStartInfo().getScopes()));
        assertThat(
                response.getClient().getServiceType(),
                equalTo(getClientStartInfo().getServiceType()));
        assertThat(
                response.getClient().getCookieConsentShared(),
                equalTo(getClientStartInfo().getCookieConsentShared()));
        assertThat(response.getClient().getRedirectUri(), equalTo(REDIRECT_URL));
        assertFalse(response.getClient().isOneLoginService());
        assertThat(
                response.getUser().isConsentRequired(), equalTo(userStartInfo.isConsentRequired()));
        assertThat(
                response.getUser().isIdentityRequired(),
                equalTo(userStartInfo.isIdentityRequired()));
        assertThat(
                response.getUser().isUpliftRequired(), equalTo(userStartInfo.isUpliftRequired()));
        assertThat(response.getUser().getCookieConsent(), equalTo(cookieConsentValue));
        assertThat(response.getUser().getGaCrossDomainTrackingId(), equalTo(gaTrackingId));
        if (isFeaturesEnabled) {
            assertThat(response.getFeatures(), not(equalTo(null)));
            assertThat(response.getFeatures().getUpdatePasswordHintTextVersion(), equalTo("1"));
        } else {
            assertThat(response.getFeatures(), equalTo(null));
        }

        verify(auditService)
                .submitAuditEvent(
                        FrontendAuditableEvent.START_INFO_FOUND,
                        CLIENT_SESSION_ID,
                        SESSION_ID,
                        TEST_CLIENT_ID,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        PERSISTENT_ID,
                        pair("internalSubjectId", AuditService.UNKNOWN));
    }

    @Test
    void shouldReturn200WhenDocCheckingAppUserIsPresent()
            throws ParseException, Json.JsonException {
        when(userContext.getClientSession()).thenReturn(docAppClientSession);
        when(configurationService.getDocAppDomain()).thenReturn(URI.create("https://doc-app"));
        when(startService.validateSession(session, CLIENT_SESSION_ID)).thenReturn(session);
        var userStartInfo = new UserStartInfo(false, false, false, false, null, null, true, null);
        when(startService.buildUserContext(session, docAppClientSession)).thenReturn(userContext);
        when(startService.buildClientStartInfo(userContext))
                .thenReturn(
                        new ClientStartInfo(
                                TEST_CLIENT_NAME,
                                DOC_APP_SCOPE.toStringList(),
                                "MANDATORY",
                                false,
                                REDIRECT_URL,
                                STATE,
                                false));
        when(startService.getGATrackingId(anyMap())).thenReturn(null);
        when(startService.getCookieConsentValue(anyMap(), anyString())).thenReturn(null);
        when(startService.buildUserStartInfo(userContext, null, null, true))
                .thenReturn(userStartInfo);
        usingValidDocAppClientSession();
        usingValidSession();

        Map<String, String> headers = new HashMap<>();
        headers.put(PersistentIdHelper.PERSISTENT_ID_HEADER_NAME, PERSISTENT_ID);
        headers.put(CLIENT_SESSION_ID_HEADER, CLIENT_SESSION_ID);
        headers.put(SESSION_ID_HEADER, SESSION_ID);
        var event = new APIGatewayProxyRequestEvent();
        event.setHeaders(headers);
        event.setRequestContext(contextWithSourceIp("123.123.123.123"));
        var result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(200));

        var response = objectMapper.readValue(result.getBody(), StartResponse.class);

        assertThat(response.getClient().getClientName(), equalTo(TEST_CLIENT_NAME));
        assertThat(response.getClient().getScopes(), equalTo(DOC_APP_SCOPE.toStringList()));
        assertThat(
                response.getClient().getServiceType(), equalTo(ServiceType.MANDATORY.toString()));
        assertThat(response.getClient().getRedirectUri(), equalTo(REDIRECT_URL));
        assertFalse(response.getClient().getCookieConsentShared());
        assertTrue(response.getUser().isDocCheckingAppUser());
        assertFalse(response.getUser().isIdentityRequired());
        assertFalse(response.getUser().isUpliftRequired());
        assertFalse(response.getUser().isAuthenticated());
        assertFalse(response.getUser().isConsentRequired());
        assertThat(response.getUser().getCookieConsent(), equalTo(null));
        assertThat(response.getUser().getGaCrossDomainTrackingId(), equalTo(null));
        verify(clientSessionService).saveClientSession(anyString(), any());

        verify(auditService)
                .submitAuditEvent(
                        FrontendAuditableEvent.START_INFO_FOUND,
                        CLIENT_SESSION_ID,
                        SESSION_ID,
                        TEST_CLIENT_ID,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        PERSISTENT_ID,
                        pair("internalSubjectId", AuditService.UNKNOWN));
    }

    @Test
    void shouldReturn400WhenClientSessionIsNotFound() throws Json.JsonException {
        usingInvalidClientSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of(CLIENT_SESSION_ID_HEADER, CLIENT_SESSION_ID));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(400));

        String expectedResponse = objectMapper.writeValueAsString(ErrorResponse.ERROR_1018);
        assertThat(result, hasBody(expectedResponse));

        verifyNoInteractions(auditService);
    }

    @Test
    void shouldReturn400WhenSessionIsNotFound() throws Json.JsonException {
        usingValidClientSession();
        usingInvalidSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put(PersistentIdHelper.PERSISTENT_ID_HEADER_NAME, PERSISTENT_ID);
        headers.put(CLIENT_SESSION_ID_HEADER, CLIENT_SESSION_ID);
        headers.put(SESSION_ID_HEADER, SESSION_ID);
        event.setHeaders(headers);
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(400));

        String expectedResponse = objectMapper.writeValueAsString(ErrorResponse.ERROR_1000);
        assertThat(result, hasBody(expectedResponse));

        verifyNoInteractions(auditService);
    }

    @Test
    void shouldReturn400WhenBuildClientStartInfoThrowsException()
            throws ParseException, Json.JsonException {
        when(startService.validateSession(session, CLIENT_SESSION_ID)).thenReturn(session);
        when(startService.buildUserContext(session, clientSession)).thenReturn(userContext);
        when(startService.buildClientStartInfo(userContext))
                .thenThrow(new ParseException("Unable to parse authentication request"));
        usingValidClientSession();
        usingValidSession();

        Map<String, String> headers = new HashMap<>();
        headers.put(PersistentIdHelper.PERSISTENT_ID_HEADER_NAME, PERSISTENT_ID);
        headers.put(CLIENT_SESSION_ID_HEADER, CLIENT_SESSION_ID);
        headers.put(SESSION_ID_HEADER, SESSION_ID);
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(headers);
        event.setRequestContext(contextWithSourceIp("123.123.123.123"));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(400));

        String expectedResponse = objectMapper.writeValueAsString(ErrorResponse.ERROR_1038);
        assertThat(result, hasBody(expectedResponse));

        verifyNoInteractions(auditService);
    }

    private void usingValidClientSession() {
        when(clientSessionService.getClientSessionFromRequestHeaders(anyMap()))
                .thenReturn(Optional.of(clientSession));
    }

    private void usingValidDocAppClientSession() {
        when(clientSessionService.getClientSessionFromRequestHeaders(anyMap()))
                .thenReturn(Optional.of(docAppClientSession));
    }

    private void usingInvalidClientSession() {
        when(clientSessionService.getClientSessionFromRequestHeaders(anyMap()))
                .thenReturn(Optional.empty());
    }

    private void usingValidSession() {
        when(sessionService.getSessionFromRequestHeaders(anyMap()))
                .thenReturn(Optional.of(session));
    }

    private void usingInvalidSession() {
        when(sessionService.getSessionFromRequestHeaders(anyMap())).thenReturn(Optional.empty());
    }

    private ClientSession getClientSession() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        AuthenticationRequest authRequest =
                new AuthenticationRequest.Builder(
                                responseType,
                                scope,
                                new ClientID(TEST_CLIENT_ID),
                                URI.create("http://localhost/redirect"))
                        .build();
        return new ClientSession(
                authRequest.toParameters(), null, mock(VectorOfTrust.class), TEST_CLIENT_NAME);
    }

    private ClientSession getDocAppClientSession() {
        try {
            var jwtClaimsSet =
                    new JWTClaimsSet.Builder()
                            .audience(AUDIENCE)
                            .subject(new Subject().getValue())
                            .claim("redirect_uri", REDIRECT_URL)
                            .claim("response_type", ResponseType.CODE.toString())
                            .claim("scope", DOC_APP_SCOPE.toString())
                            .claim("nonce", NONCE)
                            .claim("state", STATE)
                            .claim("client_id", CLIENT_ID)
                            .issuer(new ClientID("test-id").getValue())
                            .build();
            var jwsHeader = new JWSHeader(JWSAlgorithm.RS256);
            var signedJWT = new SignedJWT(jwsHeader, jwtClaimsSet);
            var signer = new RSASSASigner(KeyPairHelper.GENERATE_RSA_KEY_PAIR().getPrivate());
            signedJWT.sign(signer);
            var authRequest =
                    new AuthenticationRequest.Builder(
                                    ResponseType.CODE, DOC_APP_SCOPE, CLIENT_ID, REDIRECT_URL)
                            .state(STATE)
                            .nonce(new Nonce())
                            .requestObject(signedJWT)
                            .build();
            return new ClientSession(
                    authRequest.toParameters(),
                    LocalDateTime.now(),
                    mock(VectorOfTrust.class),
                    TEST_CLIENT_NAME);
        } catch (JOSEException e) {
            throw new RuntimeException(e);
        }
    }

    private ClientStartInfo getClientStartInfo() {
        Scope scope = new Scope(OIDCScopeValue.OPENID.getValue());

        return new ClientStartInfo(
                TEST_CLIENT_NAME,
                scope.toStringList(),
                "MANDATORY",
                false,
                REDIRECT_URL,
                STATE,
                false);
    }

    private UserStartInfo getUserStartInfo(String cookieConsent, String gaCrossDomainTrackingId) {
        return new UserStartInfo(
                true, false, false, true, cookieConsent, gaCrossDomainTrackingId, false, null);
    }
}
